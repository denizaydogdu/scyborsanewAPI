package com.scyborsa.api.service.enrichment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.config.FintablesApiConfig;
import com.scyborsa.api.dto.enrichment.BrokerageTakasListResponseDto;
import com.scyborsa.api.dto.enrichment.FintablesTakasListDto;
import com.scyborsa.api.utils.BistCacheUtils;

import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
 * @see BrokerageTakasListResponseDto
 */
@Slf4j
@Service
public class BrokerageTakasListService {

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

    /**
     * Constructor injection ile bagimliliklari alir ve OkHttp client'i olusturur.
     *
     * @param config       Fintables API konfigurasyonu
     * @param objectMapper JSON parse icin Jackson ObjectMapper
     */
    public BrokerageTakasListService(FintablesApiConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
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
