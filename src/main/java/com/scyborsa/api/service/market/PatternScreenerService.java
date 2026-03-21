package com.scyborsa.api.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.config.DjangoApiConfig;
import com.scyborsa.api.dto.market.PatternFormationDto;
import com.scyborsa.api.service.client.VelzonApiClient;
import com.scyborsa.api.utils.BistCacheUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Formasyon tarama servisi.
 *
 * <p>Django (velzon-django) pattern screener API'sinden formasyon verilerini ceker,
 * adaptive TTL ile cache'ler ve score'a gore siralar.</p>
 *
 * <p>Cache stratejisi: Seans saatlerinde 2dk, seans disinda 1 saat
 * ({@link BistCacheUtils#getAdaptiveTTL(long, long)}).</p>
 *
 * @see com.scyborsa.api.config.DjangoApiConfig
 * @see com.scyborsa.api.dto.market.PatternFormationDto
 */
@Slf4j
@Service
public class PatternScreenerService {

    private final DjangoApiConfig djangoConfig;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final VelzonApiClient velzonApiClient;

    private static final String PATTERN_SCREENER_PATH = "/ajax/pattern-screener-data/";
    private static final String SCREENER_PATH = "/api/screener/ALL_STOCKS_WITH_INDICATORS";

    /** Cache'lenmiş formasyon listesi. */
    private volatile List<PatternFormationDto> cachedPatterns;
    /** Cache'in son guncellenme zamani (epoch ms). */
    private volatile long cacheTimestamp;
    /** Cache yenileme icin kilit nesnesi. */
    private final Object cacheLock = new Object();

    /** StockCode → logoid esleme cache'i. */
    private volatile Map<String, String> logoidCache = Map.of();
    /** Logoid cache son guncelleme zamani. */
    private volatile long logoidCacheTimestamp;

    /**
     * PatternScreenerService constructor.
     *
     * @param djangoConfig    Django API konfigurasyonu
     * @param objectMapper    JSON parser
     * @param velzonApiClient api.velzon.tr REST istemcisi (logoid icin)
     */
    public PatternScreenerService(DjangoApiConfig djangoConfig,
                                   ObjectMapper objectMapper,
                                   VelzonApiClient velzonApiClient) {
        this.djangoConfig = djangoConfig;
        this.objectMapper = objectMapper;
        this.velzonApiClient = velzonApiClient;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(djangoConfig.getConnectTimeoutSeconds()))
                .build();
    }

    /**
     * Tum formasyon verilerini dondurur (cache'den).
     *
     * @return formasyon listesi, score'a gore azalan sirada; veri yoksa bos liste
     */
    public List<PatternFormationDto> getPatterns() {
        refreshCacheIfStale();
        refreshLogoidCacheIfStale();
        List<PatternFormationDto> patterns = cachedPatterns;
        if (patterns == null) {
            return List.of();
        }

        // Logoid zenginlestirme
        Map<String, String> logoidMap = logoidCache;
        if (!logoidMap.isEmpty()) {
            patterns.forEach(p -> p.setLogoid(logoidMap.get(p.getSymbol())));
        }

        return patterns;
    }

    /**
     * Logoid cache'ini gerektiginde yeniler (6 saatlik TTL).
     * VelzonApiClient uzerinden ALL_STOCKS verisinden logoid bilgisi cikarilir.
     */
    private void refreshLogoidCacheIfStale() {
        long now = System.currentTimeMillis();
        // Logoid 6 saatte bir yenilensin (nadiren degisir)
        if (!logoidCache.isEmpty() && (now - logoidCacheTimestamp) < 21_600_000L) {
            return;
        }

        try {
            String responseBody = velzonApiClient.get(SCREENER_PATH);
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode dataWrapper = root.get("data");
            if (dataWrapper == null || !dataWrapper.isObject()) return;

            JsonNode columns = dataWrapper.get("columns");
            JsonNode dataArray = dataWrapper.get("data");
            if (columns == null || dataArray == null) return;

            // Kolon index'lerini bul
            int nameIdx = -1, logoidIdx = -1;
            for (int i = 0; i < columns.size(); i++) {
                String col = columns.get(i).asText();
                if ("name".equals(col)) nameIdx = i;
                else if ("logoid".equals(col)) logoidIdx = i;
            }
            if (nameIdx < 0 || logoidIdx < 0) return;

            Map<String, String> map = new HashMap<>();
            for (JsonNode item : dataArray) {
                JsonNode d = item.get("d");
                if (d == null || !d.isArray()) continue;
                if (nameIdx < d.size() && logoidIdx < d.size()) {
                    String name = d.get(nameIdx).asText(null);
                    String logoid = d.get(logoidIdx).asText(null);
                    if (name != null && logoid != null) {
                        map.put(name, logoid);
                    }
                }
            }

            if (!map.isEmpty()) {
                this.logoidCache = Collections.unmodifiableMap(map);
                this.logoidCacheTimestamp = System.currentTimeMillis();
                log.info("[PATTERN-SCREENER] Logoid cache guncellendi: {} hisse", map.size());
            }
        } catch (Exception e) {
            log.warn("[PATTERN-SCREENER] Logoid cache yenileme basarisiz", e);
        }
    }

    /**
     * Cache suresi dolduysa Django API'den yeni veri ceker.
     * Double-check locking ile thread-safe cache yenileme.
     */
    private void refreshCacheIfStale() {
        long ttl = BistCacheUtils.getAdaptiveTTL(120_000L, 3_600_000L);
        long now = System.currentTimeMillis();

        if (cachedPatterns != null && (now - cacheTimestamp) < ttl) {
            return;
        }

        synchronized (cacheLock) {
            ttl = BistCacheUtils.getAdaptiveTTL(120_000L, 3_600_000L);
            now = System.currentTimeMillis();
            if (cachedPatterns != null && (now - cacheTimestamp) < ttl) {
                return;
            }

            log.info("[PATTERN-SCREENER] Cache yenileniyor (TTL: {}ms)", ttl);
            List<PatternFormationDto> freshData = fetchFromDjango();

            if (freshData != null && !freshData.isEmpty()) {
                this.cachedPatterns = Collections.unmodifiableList(freshData);
                this.cacheTimestamp = System.currentTimeMillis();
                log.info("[PATTERN-SCREENER] Cache guncellendi: {} formasyon", freshData.size());
            } else {
                log.warn("[PATTERN-SCREENER] Veri cekilemedi, mevcut cache korunuyor");
            }
        }
    }

    /**
     * Django pattern screener API'sinden formasyon verilerini ceker.
     *
     * @return formasyon listesi; hata durumunda bos liste
     */
    private List<PatternFormationDto> fetchFromDjango() {
        try {
            String url = djangoConfig.getBaseUrl() + PATTERN_SCREENER_PATH;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(djangoConfig.getRequestTimeoutSeconds()))
                    .header("Accept", "application/json")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("X-API-KEY", djangoConfig.getApiKey())
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("[PATTERN-SCREENER] Django API hatasi: status={}", response.statusCode());
                return List.of();
            }

            return parseResponse(response.body());
        } catch (Exception e) {
            log.error("[PATTERN-SCREENER] Django API cagrisi basarisiz", e);
            return List.of();
        }
    }

    /**
     * Django API response JSON'unu parse eder.
     *
     * <p>Beklenen format: {@code {"success": true, "data": [...]}}</p>
     *
     * @param responseBody ham JSON string
     * @return parse edilmis formasyon listesi (score DESC); hata durumunda bos liste
     */
    private List<PatternFormationDto> parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            boolean success = root.has("success") && root.get("success").asBoolean();
            if (!success) {
                log.warn("[PATTERN-SCREENER] Django response success=false");
                return List.of();
            }

            JsonNode dataArray = root.get("data");
            if (dataArray == null || !dataArray.isArray()) {
                log.warn("[PATTERN-SCREENER] Response'da data array bulunamadi");
                return List.of();
            }

            List<PatternFormationDto> patterns = new ArrayList<>();
            for (JsonNode item : dataArray) {
                PatternFormationDto dto = PatternFormationDto.builder()
                        .symbol(getTextOrNull(item, "symbol"))
                        .patternName(getTextOrNull(item, "pattern_name"))
                        .score(getDoubleOrNull(item, "score"))
                        .distance(getDoubleOrNull(item, "distance"))
                        .window(getIntOrNull(item, "window"))
                        .period(getTextOrNull(item, "period"))
                        .filename(getTextOrNull(item, "filename"))
                        .build();
                patterns.add(dto);
            }

            // Score'a gore azalan siralama
            patterns.sort(Comparator.comparingDouble((PatternFormationDto p) ->
                    p.getScore() != null ? p.getScore() : 0.0).reversed());

            return patterns;
        } catch (Exception e) {
            log.error("[PATTERN-SCREENER] Response parse hatasi", e);
            return List.of();
        }
    }

    /**
     * JSON node'undan string deger okur.
     *
     * @param node JSON node
     * @param field alan adi
     * @return string deger veya null
     */
    private String getTextOrNull(JsonNode node, String field) {
        JsonNode val = node.get(field);
        return (val != null && !val.isNull()) ? val.asText() : null;
    }

    /**
     * JSON node'undan double deger okur.
     *
     * @param node JSON node
     * @param field alan adi
     * @return double deger veya null
     */
    private Double getDoubleOrNull(JsonNode node, String field) {
        JsonNode val = node.get(field);
        return (val != null && val.isNumber()) ? val.asDouble() : null;
    }

    /**
     * JSON node'undan integer deger okur.
     *
     * @param node JSON node
     * @param field alan adi
     * @return integer deger veya null
     */
    private Integer getIntOrNull(JsonNode node, String field) {
        JsonNode val = node.get(field);
        return (val != null && val.isNumber()) ? val.asInt() : null;
    }
}
