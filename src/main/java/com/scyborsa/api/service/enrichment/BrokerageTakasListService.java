package com.scyborsa.api.service.enrichment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.config.FintablesApiConfig;
import com.scyborsa.api.dto.enrichment.BrokerageTakasListResponseDto;
import com.scyborsa.api.dto.enrichment.FintablesTakasListDto;
import com.scyborsa.api.model.AraciKurum;
import com.scyborsa.api.repository.AraciKurumRepository;
import com.scyborsa.api.service.client.FintablesMcpClient;
import com.scyborsa.api.service.client.FintablesMcpTokenStore;
import com.scyborsa.api.utils.BistCacheUtils;

import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Araci kurum takas (saklama) listesi servisi.
 *
 * <p>Fintables Next.js RSC endpoint'inden araci kurumlarin takas verilerini ceker,
 * donusturur ve dinamik TTL'li volatile cache ile sunar.
 * REST API olmadigi icin RSC response parse edilir.</p>
 *
 * <p>Cache stratejisi: seans ici 60s, seans disi bir sonraki seans acilisina kadar
 * (minimum 30 dakika).</p>
 *
 * @see FintablesApiConfig
 * @see FintablesMcpClient
 * @see BrokerageTakasListResponseDto
 */
@Slf4j
@Service
public class BrokerageTakasListService {

    /** Istanbul saat dilimi. */
    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");

    /** Fintables takas analizi RSC URL'i. */
    private static final String TAKAS_RSC_URL = "https://fintables.com/araci-kurumlar/takas-analizi?_rsc=1c7zq";

    // Regex kaldirildi — bracket-depth parser kullaniliyor (nested array guvenli)

    /** Next-Router-State-Tree header degeri (URL encoded). */
    private static final String NEXT_ROUTER_STATE_TREE =
            "%5B%22%22%2C%7B%22children%22%3A%5B%22(dashboard)%22%2C%7B%22children%22%3A%5B%22araci-kurumlar%22%2C%7B%22children%22%3A%5B%22takas-analizi%22%2C%7B%22children%22%3A%5B%22__PAGE__%22%2C%7B%7D%5D%7D%2Cnull%2C%22refetch%22%5D%7D%2Cnull%2Cnull%5D%2C%22modal%22%3A%5B%22__DEFAULT__%22%2C%7B%7D%2Cnull%2Cnull%5D%7D%2Cnull%2Cnull%5D%7D%2Cnull%2Cnull%5D";

    /** Seans ici cache TTL (milisaniye): 60 saniye. */
    private static final long CACHE_TTL_LIVE_MS = 60_000;

    /** Minimum seans disi cache TTL (milisaniye): 30 dakika. */
    private static final long MIN_CACHE_TTL_OFFHOURS_MS = 1_800_000;

    /** Fintables API konfigurasyonu. */
    private final FintablesApiConfig config;

    /** JSON parse icin Jackson ObjectMapper. */
    private final ObjectMapper objectMapper;

    /** HTTP istekleri icin OkHttp istemcisi. */
    private final OkHttpClient httpClient;

    /** Soguk baslatma hatasi sonrasi minimum yeniden deneme gecikmesi (milisaniye): 30 saniye. */
    private static final long COLD_START_RETRY_MS = 30_000L;

    /** Cache: son basarili response. */
    private volatile BrokerageTakasListResponseDto cachedResponse;

    /** Cache: son guncelleme zamani (epoch millis). */
    private volatile long cacheTimestamp;

    /** Cache yenileme icin kilit nesnesi. */
    private final Object cacheLock = new Object();

    /** Araci kurum repository (MCP sonuclarini zenginlestirmek icin). */
    private final AraciKurumRepository araciKurumRepository;

    /** Fintables MCP istemcisi (opsiyonel — yoksa RSC fallback kullanilir). */
    @Autowired(required = false)
    private FintablesMcpClient mcpClient;

    /** Fintables MCP token saklama bileşeni (opsiyonel — yoksa RSC fallback kullanilir). */
    @Autowired(required = false)
    private FintablesMcpTokenStore tokenStore;

    /**
     * Constructor injection ile bagimliliklari alir ve OkHttp client'i olusturur.
     *
     * @param config              Fintables API konfigurasyonu
     * @param objectMapper        JSON parse icin Jackson ObjectMapper
     * @param araciKurumRepository araci kurum repository (MCP zenginlestirme icin)
     */
    public BrokerageTakasListService(FintablesApiConfig config, ObjectMapper objectMapper,
                                     AraciKurumRepository araciKurumRepository) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.araciKurumRepository = araciKurumRepository;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(config.getRequestTimeoutSeconds(), TimeUnit.SECONDS)
                .build();
    }

    /**
     * Araci kurum takas (saklama) listesini getirir.
     *
     * <p>Dinamik TTL'li volatile cache kullanir (seans ici 60s, seans disi bir sonraki
     * seans acilisina kadar, minimum 30 dakika).</p>
     *
     * @return zenginlestirilmis takas listesi
     */
    public BrokerageTakasListResponseDto getTakasList() {
        long ttl = BistCacheUtils.getDynamicOffhoursTTL(CACHE_TTL_LIVE_MS, MIN_CACHE_TTL_OFFHOURS_MS);
        long now = System.currentTimeMillis();

        // Fast path: volatile cache hit (lock-free)
        if (cachedResponse != null && (now - cacheTimestamp) < ttl) {
            return cachedResponse;
        }

        synchronized (cacheLock) {
            // Re-check inside lock (DCL)
            ttl = BistCacheUtils.getDynamicOffhoursTTL(CACHE_TTL_LIVE_MS, MIN_CACHE_TTL_OFFHOURS_MS);
            now = System.currentTimeMillis();
            if (cachedResponse != null && (now - cacheTimestamp) < ttl) {
                return cachedResponse;
            }

            try {
                // 1. Birincil kaynak: MCP SQL sorgusu
                BrokerageTakasListResponseDto mcpResponse = tryFetchFromMcp();
                if (mcpResponse != null && mcpResponse.getTotalCount() > 0) {
                    this.cachedResponse = mcpResponse;
                    this.cacheTimestamp = System.currentTimeMillis();
                    return mcpResponse;
                }

                // 2. Fallback: RSC parse
                List<FintablesTakasListDto> rawList = fetchFromRsc();
                BrokerageTakasListResponseDto response = transform(rawList);

                // Cache guncelle
                this.cachedResponse = response;
                this.cacheTimestamp = System.currentTimeMillis();

                return response;
            } catch (Exception e) {
                log.error("[BROKERAGE-TAKAS] Veri alinamadi", e);
                if (cachedResponse != null) {
                    // Mevcut cache var — tam TTL backoff (retry storm engeli)
                    this.cacheTimestamp = System.currentTimeMillis();
                    log.warn("[BROKERAGE-TAKAS] Mevcut cache korunuyor. TTL sonrasi yeniden denenecek.");
                    return cachedResponse;
                }
                // Soguk baslatma hatasi — 30 saniye sonra yeniden dene
                this.cacheTimestamp = System.currentTimeMillis() - ttl + COLD_START_RETRY_MS;
                log.warn("[BROKERAGE-TAKAS] Soguk baslatmada veri cekilemedi, 30 saniye sonra yeniden denenecek.");
                return buildEmptyResponse();
            }
        }
    }

    /**
     * Fintables MCP uzerinden takas verilerini SQL sorgusu ile ceker.
     *
     * <p>MCP istemcisi veya token mevcut degilse veya gecersizse null dondurur.
     * Sorgu: {@code gunluk_takas_verileri} tablosundan bugunun tarihinde
     * araci kurum bazli takas hacimlerini ceker.</p>
     *
     * @return takas listesi response veya null (MCP kullanilamiyorsa/hata durumunda)
     */
    private BrokerageTakasListResponseDto tryFetchFromMcp() {
        if (mcpClient == null || tokenStore == null || !tokenStore.isTokenValid()) {
            log.debug("[BROKERAGE-TAKAS] MCP kullanilamiyor, RSC fallback kullanilacak");
            return null;
        }

        try {
            // Tarih LocalDate.now()dan geliyor — safe, SQL injection riski yok
            String todayStr = LocalDate.now(ISTANBUL_ZONE).format(DateTimeFormatter.ISO_LOCAL_DATE);

            String sql = "SELECT hisse_senedi_kodu, araci_kurum_kodu, tarih_europe_istanbul, nominal " +
                    "FROM gunluk_takas_verileri " +
                    "WHERE tarih_europe_istanbul = '" + todayStr + "' " +
                    "ORDER BY hisse_senedi_kodu, nominal DESC LIMIT 5000";

            JsonNode result = mcpClient.veriSorgula(sql, "Araci kurum takas verileri: " + todayStr);
            if (result == null) {
                log.debug("[BROKERAGE-TAKAS] MCP bos yanit dondu");
                return null;
            }

            String responseText = extractMcpResponseText(result);
            if (responseText == null || responseText.isBlank()) {
                log.debug("[BROKERAGE-TAKAS] MCP response text bos");
                return null;
            }

            List<BrokerageTakasListResponseDto.BrokerageTakasItemDto> items = parseMcpMarkdownTable(responseText);
            if (items.isEmpty()) {
                log.debug("[BROKERAGE-TAKAS] MCP'den veri parse edilemedi");
                return null;
            }

            // AraciKurum bilgileriyle zenginlestir (title, shortTitle, logoUrl)
            enrichItemsFromAraciKurum(items);

            log.info("[BROKERAGE-TAKAS] MCP'den {} kurum takas verisi alindi", items.size());

            return BrokerageTakasListResponseDto.builder()
                    .items(items)
                    .totalCount(items.size())
                    .build();

        } catch (Exception e) {
            log.warn("[BROKERAGE-TAKAS] MCP basarisiz, RSC fallback kullanilacak: {}", e.getMessage());
            return null;
        }
    }

    /**
     * MCP JSON-RPC result nesnesinden metin yanitini cikarir.
     *
     * @param result JSON-RPC result alani
     * @return metin yaniti veya null
     */
    private String extractMcpResponseText(JsonNode result) {
        try {
            JsonNode content = result.get("content");
            if (content != null && content.isArray() && !content.isEmpty()) {
                JsonNode firstContent = content.get(0);
                if (firstContent.has("text")) {
                    return firstContent.get("text").asText();
                }
            }
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("[BROKERAGE-TAKAS] MCP response text cikarma hatasi", e);
            return null;
        }
    }

    /**
     * MCP markdown tablo yanitini parse ederek takas item listesine donusturur.
     *
     * <p>Beklenen format:
     * <pre>
     * | hisse_senedi_kodu | araci_kurum_kodu | tarih_europe_istanbul | nominal |
     * |---|---|---|---|
     * | GARAN | AKY | 2026-04-02 | 150000000 |
     * </pre>
     * </p>
     *
     * <p>Araci kurum bazli toplam nominal hesaplanir ve kurum bilgileriyle zenginlestirilir.</p>
     *
     * @param rawText MCP yanit metni (markdown tablo)
     * @return parse edilmis takas item listesi (nominale gore azalan sirali)
     */
    private List<BrokerageTakasListResponseDto.BrokerageTakasItemDto> parseMcpMarkdownTable(String rawText) {
        List<BrokerageTakasListResponseDto.BrokerageTakasItemDto> result = new ArrayList<>();

        if (rawText == null || rawText.isBlank()) {
            return result;
        }

        try {
            // JSON sarmalayicisini coz
            String tableText = rawText;
            if (rawText.trim().startsWith("{")) {
                JsonNode json = objectMapper.readTree(rawText);
                if (json.has("table")) {
                    tableText = json.get("table").asText();
                } else if (json.has("content") && json.get("content").isArray()) {
                    JsonNode content = json.get("content");
                    if (!content.isEmpty() && content.get(0).has("text")) {
                        tableText = content.get(0).get("text").asText();
                        if (tableText.trim().startsWith("{")) {
                            JsonNode innerJson = objectMapper.readTree(tableText);
                            if (innerJson.has("table")) {
                                tableText = innerJson.get("table").asText();
                            }
                        }
                    }
                }
            }

            String[] lines = tableText.split("\n");
            if (lines.length < 3) {
                return result;
            }

            // Header satirindan kolon indekslerini bul
            String[] headers = parseMdRow(lines[0]);
            int idxKurum = findMdColumnIndex(headers, "araci_kurum_kodu");
            int idxNominal = findMdColumnIndex(headers, "nominal");

            if (idxKurum < 0 || idxNominal < 0) {
                log.warn("[BROKERAGE-TAKAS] MCP response header kolonu bulunamadi");
                return result;
            }

            // Kurum bazli nominal toplami hesapla
            java.util.Map<String, Double> kurumToplam = new java.util.LinkedHashMap<>();
            for (int i = 2; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty() || line.replaceAll("[|:\\-\\s]", "").isEmpty()) {
                    continue;
                }

                String[] cols = parseMdRow(line);
                if (cols.length == 0) {
                    continue;
                }

                try {
                    String kurum = safeGetMd(cols, idxKurum);
                    String nominalStr = safeGetMd(cols, idxNominal);
                    if (kurum == null || kurum.isBlank()) continue;

                    double nominal = 0;
                    if (nominalStr != null && !nominalStr.isBlank()) {
                        try {
                            nominal = Double.parseDouble(nominalStr.replace(",", "").trim());
                        } catch (NumberFormatException ex) {
                            // skip
                        }
                    }

                    kurumToplam.merge(kurum, nominal, Double::sum);
                } catch (Exception e) {
                    log.debug("[BROKERAGE-TAKAS] MCP satir parse hatasi (satir {}): {}", i, e.getMessage());
                }
            }

            // Kurum bazli item'lar olustur
            for (var entry : kurumToplam.entrySet()) {
                result.add(BrokerageTakasListResponseDto.BrokerageTakasItemDto.builder()
                        .code(entry.getKey())
                        .title(entry.getKey())
                        .shortTitle(entry.getKey())
                        .lastValue(entry.getValue())
                        .formattedLast(formatTurkishValue(entry.getValue()))
                        .build());
            }

            // Nominale gore azalan sirala
            result.sort(Comparator.comparingDouble(
                    BrokerageTakasListResponseDto.BrokerageTakasItemDto::getLastValue).reversed());

        } catch (Exception e) {
            log.error("[BROKERAGE-TAKAS] MCP markdown tablo parse hatasi", e);
        }

        return result;
    }

    /**
     * Markdown tablo satirini kolon dizisine donusturur.
     *
     * @param row markdown tablo satiri (orn: "| A | B | C |")
     * @return kolon degerleri (trim'li)
     */
    private String[] parseMdRow(String row) {
        if (row == null || !row.contains("|")) {
            return new String[0];
        }
        String trimmed = row.trim();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        String[] parts = trimmed.split("\\|");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    /**
     * Header dizisinde kolon adini arar ve indeksini dondurur.
     *
     * @param headers header dizisi
     * @param name    aranan kolon adi
     * @return kolon indeksi, bulunamazsa -1
     */
    private int findMdColumnIndex(String[] headers, String name) {
        for (int i = 0; i < headers.length; i++) {
            if (headers[i].equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Guvenli dizi erisimi (markdown parse icin).
     *
     * @param arr   dizi
     * @param index erisilecek indeks
     * @return deger veya null (indeks gecersizse veya null/None ise)
     */
    private String safeGetMd(String[] arr, int index) {
        if (index < 0 || index >= arr.length) {
            return null;
        }
        String val = arr[index].trim();
        return val.isEmpty() || "null".equalsIgnoreCase(val) || "None".equalsIgnoreCase(val) ? null : val;
    }

    /**
     * Fintables RSC endpoint'inden ham takas verilerini ceker ve parse eder.
     *
     * @return parse edilmis takas DTO listesi
     * @throws Exception HTTP veya parse hatasi durumunda
     */
    private List<FintablesTakasListDto> fetchFromRsc() throws Exception {
        Request request = new Request.Builder()
                .url(TAKAS_RSC_URL)
                .get()
                .addHeader("Rsc", "1")
                .addHeader("Next-Router-State-Tree", NEXT_ROUTER_STATE_TREE)
                .addHeader("Cookie", "auth-token=" + config.getTakasListToken())
                .addHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .addHeader("Referer", "https://fintables.com/araci-kurumlar/takas-analizi")
                .addHeader("Accept", "*/*")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("Fintables RSC HTTP " + response.code());
            }

            okhttp3.ResponseBody responseBody = response.body();
            String body = responseBody != null ? responseBody.string() : "";
            return parseRscResponse(body);
        }
    }

    /**
     * RSC response metnini parse ederek results dizisini cikarir.
     *
     * <p>RSC response cok satirli metindir. JSON verisi {@code "results":[...]}
     * formatinda bulunur. Bracket-depth parser kullanilarak nested array'ler
     * guvenli sekilde handle edilir.</p>
     *
     * @param rscBody RSC response body
     * @return parse edilmis DTO listesi
     * @throws Exception parse hatasi durumunda
     */
    private List<FintablesTakasListDto> parseRscResponse(String rscBody) throws Exception {
        // "results":[ ifadesini bul
        String marker = "\"results\":";
        int markerIdx = rscBody.indexOf(marker);
        if (markerIdx < 0) {
            // Alternatif: bosluklu format
            marker = "\"results\" :";
            markerIdx = rscBody.indexOf(marker);
        }
        if (markerIdx < 0) {
            log.warn("[BROKERAGE-TAKAS] RSC response'da 'results' dizisi bulunamadi");
            return Collections.emptyList();
        }

        // [ karakterini bul
        int bracketStart = rscBody.indexOf('[', markerIdx + marker.length());
        if (bracketStart < 0) {
            log.warn("[BROKERAGE-TAKAS] RSC response'da 'results' sonrasi '[' bulunamadi");
            return Collections.emptyList();
        }

        // Bracket-depth ile kapanan ] karakterini bul
        int depth = 0;
        int bracketEnd = -1;
        for (int i = bracketStart; i < rscBody.length(); i++) {
            char c = rscBody.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) {
                    bracketEnd = i;
                    break;
                }
            }
        }

        if (bracketEnd < 0) {
            log.warn("[BROKERAGE-TAKAS] RSC response'da 'results' dizisi kapanmadi");
            return Collections.emptyList();
        }

        String resultsJson = rscBody.substring(bracketStart, bracketEnd + 1);
        return objectMapper.readValue(resultsJson, new TypeReference<List<FintablesTakasListDto>>() {});
    }

    /**
     * Ham DTO listesini zenginlestirilmis response DTO'ya donusturur.
     *
     * @param rawList Fintables'ten gelen ham veri listesi
     * @return zenginlestirilmis response DTO
     */
    private BrokerageTakasListResponseDto transform(List<FintablesTakasListDto> rawList) {
        if (rawList == null || rawList.isEmpty()) {
            return buildEmptyResponse();
        }

        List<BrokerageTakasListResponseDto.BrokerageTakasItemDto> items = rawList.stream()
                .map(item -> {
                    double weeklyChange = 0;
                    if (item.getPrevWeek() != 0) {
                        weeklyChange = (item.getLast() - item.getPrevWeek()) / item.getPrevWeek() * 100;
                    }

                    return BrokerageTakasListResponseDto.BrokerageTakasItemDto.builder()
                            .code(item.getCode())
                            .title(item.getTitle())
                            .shortTitle(item.getShortTitle())
                            .logoUrl(item.getLogo())
                            .lastValue(item.getLast())
                            .formattedLast(formatTurkishValue(item.getLast()))
                            .prevWeek(item.getPrevWeek())
                            .prevMonth(item.getPrevMonth())
                            .prev3Months(item.getPrev3Months())
                            .percentage(item.getPercentage() * 100)
                            .weeklyChange(Math.round(weeklyChange * 100.0) / 100.0)
                            .build();
                })
                .sorted(Comparator.comparingDouble(BrokerageTakasListResponseDto.BrokerageTakasItemDto::getLastValue).reversed())
                .collect(Collectors.toList());

        return BrokerageTakasListResponseDto.builder()
                .items(items)
                .totalCount(items.size())
                .build();
    }

    /**
     * Saklama hacmi degerini Turkce formatla donusturur.
     *
     * <p>Degerler Trilyon/Milyar/Milyon/Bin olarak formatlanir.</p>
     *
     * @param value ham deger
     * @return formatlanmis Turkce string (orn: "1.87 Trilyon", "543 Milyar")
     */
    private String formatTurkishValue(double value) {
        double abs = Math.abs(value);
        String sign = value < 0 ? "-" : "";
        if (abs >= 1_000_000_000_000.0) {
            return sign + String.format("%.2f Trilyon", abs / 1_000_000_000_000.0);
        } else if (abs >= 1_000_000_000.0) {
            return sign + String.format("%.2f Milyar", abs / 1_000_000_000.0);
        } else if (abs >= 1_000_000.0) {
            return sign + String.format("%.0f Milyon", abs / 1_000_000.0);
        } else if (abs >= 1_000.0) {
            return sign + String.format("%.1f Bin", abs / 1_000.0);
        }
        return sign + String.format("%.0f", abs);
    }

    /**
     * MCP'den gelen item'lari AraciKurum entity bilgileriyle zenginlestirir.
     *
     * <p>Her item'in code alanini AraciKurum tablosundaki code ile eslestirir
     * ve title, shortTitle, logoUrl alanlarini doldurur.</p>
     *
     * @param items zenginlestirilecek takas item listesi
     */
    private void enrichItemsFromAraciKurum(List<BrokerageTakasListResponseDto.BrokerageTakasItemDto> items) {
        if (items == null || items.isEmpty() || araciKurumRepository == null) {
            return;
        }

        try {
            List<String> codes = items.stream()
                    .map(BrokerageTakasListResponseDto.BrokerageTakasItemDto::getCode)
                    .toList();

            Map<String, AraciKurum> kurumMap = araciKurumRepository.findByCodeIn(codes).stream()
                    .collect(Collectors.toMap(AraciKurum::getCode, Function.identity(), (a, b) -> a));

            for (var item : items) {
                AraciKurum kurum = kurumMap.get(item.getCode());
                if (kurum != null) {
                    item.setTitle(kurum.getTitle());
                    item.setShortTitle(kurum.getShortTitle() != null ? kurum.getShortTitle() : kurum.getTitle());
                    item.setLogoUrl(kurum.getLogoUrl());
                }
            }
        } catch (Exception e) {
            log.debug("[BROKERAGE-TAKAS] AraciKurum zenginlestirme basarisiz: {}", e.getMessage());
        }
    }

    /**
     * Bos response olusturur.
     *
     * @return tum listeleri bos olan response
     */
    private BrokerageTakasListResponseDto buildEmptyResponse() {
        return BrokerageTakasListResponseDto.builder()
                .items(Collections.emptyList())
                .totalCount(0)
                .build();
    }
}
