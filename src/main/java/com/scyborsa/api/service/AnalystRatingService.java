package com.scyborsa.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.dto.analyst.AnalystRatingDto;
import com.scyborsa.api.dto.fintables.AraciKurumTahminDto;
import com.scyborsa.api.service.client.FintablesApiClient;
import com.scyborsa.api.service.client.FintablesMcpClient;
import com.scyborsa.api.service.client.FintablesMcpTokenStore;
import com.scyborsa.api.dto.analyst.FintablesAnalystRatingResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fintables analist tavsiye verilerini saglayan servis.
 *
 * <p>{@link FintablesApiClient} kullanarak Fintables API'den
 * analist tavsiyelerini ceker ve volatile cache ile saklar
 * (varsayilan TTL: 24 saat / 86400 saniye, double-check locking).</p>
 *
 * @see FintablesApiClient
 * @see AnalystRatingDto
 */
@Slf4j
@Service
public class AnalystRatingService {

    /** Fintables API istemcisi. */
    private final FintablesApiClient fintablesApiClient;

    /** Araci kurum is mantigi servisi (sync icin). */
    private final AraciKurumService araciKurumService;

    /** Katilim endeksi uyelik kontrolu servisi. */
    private final KatilimEndeksiService katilimEndeksiService;

    /** Fintables MCP istemcisi (opsiyonel — graceful degradation). */
    @Autowired(required = false)
    private FintablesMcpClient mcpClient;

    /** Fintables MCP token saklama bileşeni (opsiyonel — graceful degradation). */
    @Autowired(required = false)
    private FintablesMcpTokenStore tokenStore;

    /** JSON parse için ObjectMapper. */
    @Autowired
    private ObjectMapper objectMapper;

    /** Cache: analist tavsiyeleri. */
    private volatile List<AnalystRatingDto> cachedRatings;

    /** Cache: son guncelleme zamani (epoch millis). */
    private volatile long cacheTimestamp;

    /** Cache kilit nesnesi. */
    private final Object cacheLock = new Object();

    /** Cache TTL (saniye cinsinden). */
    @Value("${analyst-rating.cache.ttl-seconds:86400}")
    private int cacheTtlSeconds;

    /**
     * Constructor injection ile bagimliliklari alir.
     *
     * @param fintablesApiClient    Fintables API istemcisi
     * @param araciKurumService    araci kurum sync servisi
     * @param katilimEndeksiService katilim endeksi uyelik kontrolu servisi
     */
    public AnalystRatingService(FintablesApiClient fintablesApiClient, AraciKurumService araciKurumService,
                                KatilimEndeksiService katilimEndeksiService) {
        this.fintablesApiClient = fintablesApiClient;
        this.araciKurumService = araciKurumService;
        this.katilimEndeksiService = katilimEndeksiService;
    }

    /**
     * Her sabah 09:00'da analist tavsiye cache'ini yeniler.
     *
     * <p>Fintables analist tavsiyeleri gunluk guncellenir.
     * Sabah 09:00'da taze veri cekilir, 24 saat boyunca cache'te tutulur.</p>
     */
    @Scheduled(cron = "0 0 9 * * *", zone = "Europe/Istanbul")
    public void scheduledCacheRefresh() {
        log.info("[ANALYST-RATING] Gunluk cache yenileme basladi (09:00 scheduled)");
        try {
            this.cacheTimestamp = 0;
            List<AnalystRatingDto> ratings = getAnalystRatings();
            log.info("[ANALYST-RATING] Gunluk cache yenileme tamamlandi: {} tavsiye", ratings.size());
        } catch (Exception e) {
            log.warn("[ANALYST-RATING] Gunluk cache yenileme basarisiz: {}", e.getMessage());
        }
    }

    /**
     * Tum analist tavsiyelerini dondurur.
     *
     * <p>Fintables API'den paginated olarak tum tavsiyeleri ceker.
     * Sonuclar volatile cache ile saklanir (TTL: 24 saat).</p>
     *
     * @return analist tavsiye listesi; hata durumunda bos liste
     */
    public List<AnalystRatingDto> getAnalystRatings() {
        long now = System.currentTimeMillis();
        if (cachedRatings != null && (now - cacheTimestamp) < (long) cacheTtlSeconds * 1000) {
            return cachedRatings;
        }

        List<AnalystRatingDto> freshRatings = null;
        synchronized (cacheLock) {
            now = System.currentTimeMillis();
            if (cachedRatings != null && (now - cacheTimestamp) < (long) cacheTtlSeconds * 1000) {
                return cachedRatings;
            }

            log.info("[ANALYST-RATING] Analist tavsiyeleri Fintables API'den cekiliyor");
            try {
                List<AnalystRatingDto> allRatings = fetchAllPages();

                // Katilim endeksi zenginlestirmesi
                for (AnalystRatingDto dto : allRatings) {
                    dto.setKatilim(katilimEndeksiService.isKatilim(dto.getStockCode()));
                }

                if (allRatings.isEmpty()) {
                    log.warn("[ANALYST-RATING] API bos sonuc dondurdu");
                    return cachedRatings != null ? cachedRatings : List.of();
                }

                this.cachedRatings = Collections.unmodifiableList(allRatings);
                this.cacheTimestamp = System.currentTimeMillis();
                freshRatings = this.cachedRatings;
                log.info("[ANALYST-RATING] {} tavsiye cache'lendi", allRatings.size());
            } catch (Exception e) {
                log.error("[ANALYST-RATING] API hatasi: {}", e.getMessage());
                return cachedRatings != null ? cachedRatings : List.of();
            }
        }

        // Araci kurum sync lock disinda — concurrent reader'lari bloklamaz
        if (freshRatings != null) {
            try {
                araciKurumService.syncFromAnalystRatings(freshRatings);
            } catch (Exception e) {
                log.warn("Araci kurum sync basarisiz, cache calismaya devam ediyor: {}", e.getMessage());
            }
            return freshRatings;
        }

        return cachedRatings != null ? cachedRatings : List.of();
    }

    /**
     * Fintables API'den tum sayfalari cekerek birlestir.
     *
     * <p>Pagination: {@code ?limit=100&offset=0}, {@code next} URL null olana kadar devam eder.</p>
     *
     * @return tum sayfalardaki tavsiye listesi
     * @throws Exception API veya parse hatasi durumunda
     */
    private List<AnalystRatingDto> fetchAllPages() throws Exception {
        List<AnalystRatingDto> allResults = new ArrayList<>();
        String url = "/analyst-ratings/?limit=100&offset=0";
        int maxPages = 50;
        int pageCount = 0;

        while (url != null && pageCount < maxPages) {
            FintablesAnalystRatingResponse response = fintablesApiClient.get(
                    url, new TypeReference<FintablesAnalystRatingResponse>() {}
            );

            if (response.getResults() != null) {
                allResults.addAll(response.getResults());
            }

            url = response.getNext();
            pageCount++;
            log.debug("[ANALYST-RATING] Sayfa cekildi: {} tavsiye (toplam: {})",
                    response.getResults() != null ? response.getResults().size() : 0,
                    allResults.size());
        }

        if (pageCount >= maxPages) {
            log.warn("[ANALYST-RATING] Pagination guvenlik limiti asildi ({})", maxPages);
        }

        return allResults;
    }

    // ── MCP Tahmin Sorgusu ──

    /**
     * Belirtilen hisse için aracı kurum tahminlerini MCP'den çeker.
     *
     * <p>Fintables MCP {@code hisse_senedi_araci_kurum_tahminleri} tablosundan
     * yıl ve ay sırasına göre azalan tahmin verilerini döndürür.</p>
     *
     * <p>MCP istemcisi mevcut değilse veya token geçersizse boş liste döner
     * (graceful degradation).</p>
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return aracı kurum tahmin listesi; MCP kullanılamıyorsa veya hata durumunda boş liste
     */
    public List<AraciKurumTahminDto> getTahminler(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) {
            return Collections.emptyList();
        }

        // SQL injection koruması: sadece büyük harf ve rakam kabul et
        if (!stockCode.trim().toUpperCase().matches("^[A-Z0-9]{1,10}$")) {
            log.warn("[ANALYST-RATING] Geçersiz sembol formatı: {}", stockCode);
            return Collections.emptyList();
        }

        if (mcpClient == null || tokenStore == null || !tokenStore.isTokenValid()) {
            log.debug("[ANALYST-RATING] MCP kullanılamıyor, tahminler alınamadı: stockCode={}", stockCode);
            return Collections.emptyList();
        }

        try {
            String sql = "SELECT * FROM hisse_senedi_araci_kurum_tahminleri " +
                    "WHERE hisse_senedi_kodu = '" + stockCode.toUpperCase() + "' " +
                    "ORDER BY yil DESC, ay DESC";

            JsonNode result = mcpClient.veriSorgula(sql, "Aracı kurum tahminleri: " + stockCode);
            if (result == null) {
                return Collections.emptyList();
            }

            String responseText = extractMcpResponseText(result);
            if (responseText == null || responseText.isBlank()) {
                return Collections.emptyList();
            }

            List<AraciKurumTahminDto> tahminler = parseTahminMarkdownTable(responseText);
            log.info("[ANALYST-RATING] MCP'den {} tahmin alındı: stockCode={}", tahminler.size(), stockCode);
            return tahminler;
        } catch (Exception e) {
            log.warn("[ANALYST-RATING] MCP tahmin sorgusu hatası: stockCode={}, hata={}", stockCode, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * MCP JSON-RPC result nesnesinden metin yanıtını çıkarır.
     *
     * @param result JSON-RPC result alanı
     * @return metin yanıtı veya null
     */
    private String extractMcpResponseText(JsonNode result) {
        if (result == null || objectMapper == null) {
            return null;
        }
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
            log.warn("[ANALYST-RATING] MCP response text çıkarma hatası", e);
            return null;
        }
    }

    /**
     * MCP markdown tablo yanıtını parse ederek AraciKurumTahminDto listesine dönüştürür.
     *
     * <p>Beklenen kolon adları: hisse_senedi_kodu, araci_kurum_kodu, yil, ay,
     * satislar, favok, net_kar.</p>
     *
     * @param rawText MCP yanıt metni (markdown tablo)
     * @return parse edilmiş tahmin listesi
     */
    private List<AraciKurumTahminDto> parseTahminMarkdownTable(String rawText) {
        List<AraciKurumTahminDto> result = new ArrayList<>();
        if (rawText == null || rawText.isBlank() || objectMapper == null) {
            return result;
        }

        try {
            // JSON sarmalayıcısını çöz
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

            // Header satırından kolon indekslerini bul
            String[] headers = parseMdRow(lines[0]);
            int idxKod = findMdColumnIndex(headers, "hisse_senedi_kodu");
            int idxKurum = findMdColumnIndex(headers, "araci_kurum_kodu");
            int idxYil = findMdColumnIndex(headers, "yil");
            int idxAy = findMdColumnIndex(headers, "ay");
            int idxSatislar = findMdColumnIndex(headers, "satislar");
            int idxFavok = findMdColumnIndex(headers, "favok");
            int idxNetKar = findMdColumnIndex(headers, "net_kar");

            // Satır 0 = header, satır 1 = ayırıcı, satır 2+ = veri
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
                    result.add(AraciKurumTahminDto.builder()
                            .hisseSenediKodu(safeGetMd(cols, idxKod))
                            .araciKurumKodu(safeGetMd(cols, idxKurum))
                            .yil(parseMdInteger(safeGetMd(cols, idxYil)))
                            .ay(parseMdInteger(safeGetMd(cols, idxAy)))
                            .satislar(parseMdDouble(safeGetMd(cols, idxSatislar)))
                            .favok(parseMdDouble(safeGetMd(cols, idxFavok)))
                            .netKar(parseMdDouble(safeGetMd(cols, idxNetKar)))
                            .build());
                } catch (Exception e) {
                    log.debug("[ANALYST-RATING] Tahmin satır parse hatası (satır {}): {}", i, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[ANALYST-RATING] Tahmin markdown tablo parse hatası", e);
        }

        return result;
    }

    /**
     * Markdown tablo satırını kolon dizisine dönüştürür.
     *
     * @param row markdown tablo satırı (ör: "| A | B | C |")
     * @return kolon değerleri (trim'li)
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
     * Header dizisinde kolon adını arar ve indeksini döndürür.
     *
     * @param headers header dizisi
     * @param name    aranan kolon adı
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
     * Güvenli dizi erişimi (markdown parse için).
     *
     * @param arr   dizi
     * @param index erişilecek indeks
     * @return değer veya null (indeks geçersizse veya null/None ise)
     */
    private String safeGetMd(String[] arr, int index) {
        if (index < 0 || index >= arr.length) {
            return null;
        }
        String val = arr[index].trim();
        return val.isEmpty() || "null".equalsIgnoreCase(val) || "None".equalsIgnoreCase(val) ? null : val;
    }

    /**
     * String'i Double'a parse eder.
     *
     * @param value string değer
     * @return Double değer veya null
     */
    private Double parseMdDouble(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Double.parseDouble(value.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * String'i Integer'a parse eder.
     *
     * @param value string değer
     * @return Integer değer veya null
     */
    private Integer parseMdInteger(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String cleaned = value.replace(",", "").trim();
            int dotIdx = cleaned.indexOf('.');
            if (dotIdx > 0) {
                cleaned = cleaned.substring(0, dotIdx);
            }
            return Integer.parseInt(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
