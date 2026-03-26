package com.scyborsa.api.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.config.DjangoApiConfig;
import com.scyborsa.api.dto.market.RegressionChannelDto;
import com.scyborsa.api.service.KatilimEndeksiService;
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
 * Regresyon kanali tarama servisi.
 *
 * <p>Django (velzon-django) indicator screener API'sinden regresyon kanali verilerini ceker,
 * adaptive TTL ile cache'ler ve R-kare degerine gore siralar.</p>
 *
 * <p>Cache stratejisi: Seans saatlerinde 2dk, seans disinda 1 saat
 * ({@link BistCacheUtils#getAdaptiveTTL(long, long)}).</p>
 *
 * @see com.scyborsa.api.config.DjangoApiConfig
 * @see com.scyborsa.api.dto.market.RegressionChannelDto
 */
@Slf4j
@Service
public class RegressionScreenerService {

    private final DjangoApiConfig djangoConfig;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final VelzonApiClient velzonApiClient;
    private final KatilimEndeksiService katilimEndeksiService;

    private static final String INDICATOR_SCREENER_PATH = "/screener/ajax/indicator-screener/?timeframe=1d";
    private static final String SCREENER_PATH = "/api/screener/ALL_STOCKS_WITH_INDICATORS";

    /** Cache'lenmis regresyon listesi. */
    private volatile List<RegressionChannelDto> cachedRegressions;
    /** Cache'in son guncellenme zamani (epoch ms). */
    private volatile long cacheTimestamp;
    /** Cache yenileme icin kilit nesnesi. */
    private final Object cacheLock = new Object();
    /** Logoid cache yenileme icin kilit nesnesi. */
    private final Object logoidLock = new Object();

    /** StockCode → logoid esleme cache'i. */
    private volatile Map<String, String> logoidCache = Map.of();
    /** Logoid cache son guncelleme zamani. */
    private volatile long logoidCacheTimestamp;

    /**
     * RegressionScreenerService constructor.
     *
     * @param djangoConfig         Django API konfigurasyonu
     * @param objectMapper         JSON parser
     * @param velzonApiClient      api.velzon.tr REST istemcisi (logoid icin)
     * @param katilimEndeksiService katilim endeksi uyelik kontrolu servisi
     */
    public RegressionScreenerService(DjangoApiConfig djangoConfig,
                                      ObjectMapper objectMapper,
                                      VelzonApiClient velzonApiClient,
                                      KatilimEndeksiService katilimEndeksiService) {
        this.djangoConfig = djangoConfig;
        this.objectMapper = objectMapper;
        this.velzonApiClient = velzonApiClient;
        this.katilimEndeksiService = katilimEndeksiService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(djangoConfig.getConnectTimeoutSeconds()))
                .build();
    }

    /**
     * Tum regresyon kanali verilerini dondurur (cache'den).
     *
     * @return regresyon listesi, R-kare degerine gore azalan sirada; veri yoksa bos liste
     */
    public List<RegressionChannelDto> getRegressions() {
        refreshCacheIfStale();
        refreshLogoidCacheIfStale();
        List<RegressionChannelDto> regressions = cachedRegressions;
        if (regressions == null) {
            return List.of();
        }

        // Logoid zenginlestirme (defensive copy — cache'deki DTO'lari mutate etme)
        Map<String, String> logoidMap = logoidCache;
        List<RegressionChannelDto> result = new ArrayList<>(regressions.size());
        for (RegressionChannelDto r : regressions) {
            RegressionChannelDto copy = RegressionChannelDto.builder()
                    .symbol(r.getSymbol())
                    .period(r.getPeriod())
                    .r2(r.getR2())
                    .slope(r.getSlope())
                    .position(r.getPosition())
                    .pctPosition(r.getPctPosition())
                    .logoid(!logoidMap.isEmpty() ? logoidMap.get(r.getSymbol()) : r.getLogoid())
                    .katilim(katilimEndeksiService.isKatilim(r.getSymbol()))
                    .build();
            result.add(copy);
        }

        return result;
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

        synchronized (logoidLock) {
            // Double-check locking
            now = System.currentTimeMillis();
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
                    log.info("[REGRESSION-SCREENER] Logoid cache guncellendi: {} hisse", map.size());
                }
            } catch (Exception e) {
                log.warn("[REGRESSION-SCREENER] Logoid cache yenileme basarisiz", e);
            }
        } // synchronized (logoidLock)
    }

    /**
     * Cache suresi dolduysa Django API'den yeni veri ceker.
     * Double-check locking ile thread-safe cache yenileme.
     */
    private void refreshCacheIfStale() {
        long ttl = BistCacheUtils.getAdaptiveTTL(120_000L, 3_600_000L);
        long now = System.currentTimeMillis();

        if (cachedRegressions != null && (now - cacheTimestamp) < ttl) {
            return;
        }

        synchronized (cacheLock) {
            ttl = BistCacheUtils.getAdaptiveTTL(120_000L, 3_600_000L);
            now = System.currentTimeMillis();
            if (cachedRegressions != null && (now - cacheTimestamp) < ttl) {
                return;
            }

            log.info("[REGRESSION-SCREENER] Cache yenileniyor (TTL: {}ms)", ttl);
            List<RegressionChannelDto> freshData = new ArrayList<>(fetchRegressionData());

            // R-kare degerine gore azalan siralama (null r2 sona)
            freshData.sort(Comparator.comparing(
                    RegressionChannelDto::getR2,
                    Comparator.nullsLast(Comparator.reverseOrder())));

            if (!freshData.isEmpty()) {
                this.cachedRegressions = Collections.unmodifiableList(freshData);
                this.cacheTimestamp = System.currentTimeMillis();
                log.info("[REGRESSION-SCREENER] Cache guncellendi: {} regresyon", freshData.size());
            } else {
                log.warn("[REGRESSION-SCREENER] Veri cekilemedi, mevcut cache korunuyor");
            }
        }
    }

    /**
     * Django indicator screener API'sinden regresyon kanali verilerini ceker.
     *
     * @return regresyon listesi; hata durumunda bos liste
     */
    private List<RegressionChannelDto> fetchRegressionData() {
        try {
            String url = djangoConfig.getBaseUrl() + INDICATOR_SCREENER_PATH;
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
                log.error("[REGRESSION-SCREENER] Indicator screener API hatasi: status={}", response.statusCode());
                return List.of();
            }

            return parseRegressionResponse(response.body());
        } catch (Exception e) {
            log.error("[REGRESSION-SCREENER] Indicator screener API cagrisi basarisiz", e);
            return List.of();
        }
    }

    /**
     * Indicator screener response'undan regresyon verilerini parse eder.
     *
     * <p>Beklenen format: {@code {"data": {"stocks_data": [{"symbol": "...", "regression": {...}}, ...]}}}</p>
     *
     * @param responseBody ham JSON string
     * @return parse edilmis regresyon listesi; hata durumunda bos liste
     */
    private List<RegressionChannelDto> parseRegressionResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode data = root.get("data");
            if (data == null) return List.of();

            JsonNode stocksData = data.get("stocks_data");
            if (stocksData == null || !stocksData.isArray()) return List.of();

            List<RegressionChannelDto> regressions = new ArrayList<>();
            for (JsonNode stock : stocksData) {
                JsonNode regression = stock.get("regression");
                if (regression == null || regression.isNull()) continue;

                String symbol = stock.has("symbol") ? stock.get("symbol").asText("") : "";
                if (symbol.isEmpty()) continue;

                RegressionChannelDto dto = RegressionChannelDto.builder()
                        .symbol(symbol)
                        .period(getIntOrNull(regression, "period"))
                        .r2(getDoubleOrNull(regression, "r2"))
                        .slope(getTextOrNull(regression, "slope"))
                        .position(getTextOrNull(regression, "position"))
                        .pctPosition(getDoubleOrNull(regression, "pct_position"))
                        .build();
                regressions.add(dto);
            }

            log.info("[REGRESSION-SCREENER] {} hisse icin regresyon verisi parse edildi", regressions.size());
            return regressions;
        } catch (Exception e) {
            log.error("[REGRESSION-SCREENER] Response parse hatasi", e);
            return List.of();
        }
    }

    /**
     * JSON node'undan string deger okur.
     *
     * @param node  JSON node
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
     * @param node  JSON node
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
     * @param node  JSON node
     * @param field alan adi
     * @return integer deger veya null
     */
    private Integer getIntOrNull(JsonNode node, String field) {
        JsonNode val = node.get(field);
        return (val != null && val.isNumber()) ? val.asInt() : null;
    }
}
