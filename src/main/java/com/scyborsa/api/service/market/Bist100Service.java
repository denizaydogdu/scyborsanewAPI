package com.scyborsa.api.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.config.TradingViewConfig;
import com.scyborsa.api.dto.sector.SectorStockDto;
import com.scyborsa.api.service.KatilimEndeksiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BIST endeks hisse listelerini TradingView Scanner API uzerinden saglayan servis.
 *
 * <p>TradingView Scanner API'sine tek bir HTTP POST istegi gondererek tum BIST
 * hisselerini {@code indexes} column'u ile birlikte ceker. Sonuclar volatile
 * cache ile saklanir (varsayilan TTL: 120 saniye). Her endeks icin
 * (BIST 100, BIST 50, BIST 30) ayri filtreleme uygulanir.</p>
 *
 * <p>TradingView Scanner API'de {@code indexes} alani yalnizca column olarak
 * desteklenir, filter'da kullanilamaz. Bu nedenle tum hisseler cekilip
 * client-side proname filtresi uygulanir.</p>
 *
 * @see SectorStockDto
 * @see TradingViewConfig
 */
@Slf4j
@Service
public class Bist100Service {

    /** BIST endeks proname sabitleri. */
    private static final String XU100_PRONAME = "BIST:XU100";
    private static final String XU050_PRONAME = "BIST:XU050";
    private static final String XU030_PRONAME = "BIST:XU030";

    /** TradingView API konfigurasyonu (URL, cookie, header). */
    private final TradingViewConfig tradingViewConfig;

    /** JSON parse icin Jackson ObjectMapper. */
    private final ObjectMapper objectMapper;

    /** Katilim endeksi uyelik kontrolu servisi. */
    private final KatilimEndeksiService katilimEndeksiService;

    /** HTTP istekleri icin Java 11 HttpClient. */
    private final HttpClient httpClient;

    /** TradingView Scanner API URL'i. */
    private final String screenerUrl;

    /** Raw cache: tum hisseler (ticker -> index pronames + stock data). */
    private volatile List<StockWithIndexes> cachedAllStocks;

    /** Cache: son guncelleme zamani (epoch millis). */
    private volatile long cacheTimestamp;

    /** Endeks bazli filtrelenmis cache. Cache yenilendiginde temizlenir. */
    private final Map<String, List<SectorStockDto>> indexCache = new ConcurrentHashMap<>();

    /** Logoid cache: ticker → logoid haritasi. Cache yenilendiginde temizlenir. */
    private volatile Map<String, String> cachedLogoidMap;

    /** Cache yenileme kilit nesnesi. */
    private final Object cacheLock = new Object();

    /** Cache TTL (saniye cinsinden). */
    @Value("${bist100.cache.ttl-seconds:120}")
    private int cacheTtlSeconds;

    /**
     * Constructor injection ile bagimliliklari alir ve HTTP client olusturur.
     *
     * @param tradingViewConfig    TradingView API konfigurasyonu (URL, cookie, header)
     * @param objectMapper         JSON parse icin Jackson ObjectMapper
     * @param katilimEndeksiService katilim endeksi uyelik kontrolu servisi
     */
    public Bist100Service(TradingViewConfig tradingViewConfig,
                          ObjectMapper objectMapper,
                          KatilimEndeksiService katilimEndeksiService) {
        this.tradingViewConfig = tradingViewConfig;
        this.objectMapper = objectMapper;
        this.katilimEndeksiService = katilimEndeksiService;
        this.screenerUrl = tradingViewConfig.getScreenerApiUrl();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(tradingViewConfig.getHttpConnectTimeoutSeconds()))
                .build();
    }

    /**
     * Tum BIST hisselerini dondurur.
     *
     * @return tum hisse listesi (isme gore sirali); hata durumunda bos liste
     */
    public List<SectorStockDto> getAllStocks() {
        ensureCacheLoaded();
        if (cachedAllStocks == null) return List.of();
        return indexCache.computeIfAbsent("ALL", key -> {
            List<SectorStockDto> all = new ArrayList<>();
            for (StockWithIndexes s : cachedAllStocks) {
                all.add(s.dto);
            }
            log.info("[BIST-INDEX] Tum hisseler: {} adet", all.size());
            return Collections.unmodifiableList(all);
        });
    }

    /**
     * BIST 100 endeksine ait hisse listesini dondurur.
     *
     * @return BIST 100 hisse listesi (isme gore sirali); hata durumunda bos liste
     */
    public List<SectorStockDto> getBist100Stocks() {
        return getStocksByIndex(XU100_PRONAME);
    }

    /**
     * BIST 50 endeksine ait hisse listesini dondurur.
     *
     * @return BIST 50 hisse listesi (isme gore sirali); hata durumunda bos liste
     */
    public List<SectorStockDto> getBist50Stocks() {
        return getStocksByIndex(XU050_PRONAME);
    }

    /**
     * BIST 30 endeksine ait hisse listesini dondurur.
     *
     * @return BIST 30 hisse listesi (isme gore sirali); hata durumunda bos liste
     */
    public List<SectorStockDto> getBist30Stocks() {
        return getStocksByIndex(XU030_PRONAME);
    }

    /**
     * Belirtilen endeks proname'ine gore filtrelenmis hisse listesini dondurur.
     *
     * <p>Tek bir TradingView API cagrisinin sonucu cache'lenir ve her endeks icin
     * ayri filtreleme uygulanir. Endeks bazli sonuclar da ayrica cache'lenir.</p>
     *
     * @param indexProname endeks proname degeri (orn. "BIST:XU100")
     * @return filtrelenmis hisse listesi; hata durumunda bos liste
     */
    private List<SectorStockDto> getStocksByIndex(String indexProname) {
        ensureCacheLoaded();

        if (cachedAllStocks == null) {
            return List.of();
        }

        return indexCache.computeIfAbsent(indexProname, this::filterByIndex);
    }

    /**
     * Cache'in guncel olmasini saglar. TTL dolmussa yeniden fetch eder.
     *
     * <p>Double-check locking ile thread-safe cache yenileme. Lock disindaki
     * ilk kontrol gereksiz lock'i onler, lock icindeki ikinci kontrol
     * es zamanli fetch'i onler.</p>
     */
    private void ensureCacheLoaded() {
        long now = System.currentTimeMillis();
        if (cachedAllStocks != null && (now - cacheTimestamp) < (long) cacheTtlSeconds * 1000) {
            return;
        }

        synchronized (cacheLock) {
            // Double-check: baska thread araya girip cache'i guncellemis olabilir
            now = System.currentTimeMillis();
            if (cachedAllStocks != null && (now - cacheTimestamp) < (long) cacheTtlSeconds * 1000) {
                return;
            }

            log.info("[BIST-INDEX] Tum hisse verileri guncelleniyor");
            List<StockWithIndexes> stocks = fetchAllStocks();

            if (stocks.isEmpty()) {
                log.warn("[BIST-INDEX] TradingView taramasi bos sonuc dondurdu");
                return;
            }

            this.indexCache.clear();
            this.cachedLogoidMap = null;
            this.cachedAllStocks = stocks;
            this.cacheTimestamp = System.currentTimeMillis();

            log.info("[BIST-INDEX] {} hisse basariyla cekildi", stocks.size());
        }
    }

    /**
     * Belirtilen endeks proname'ine uye olan hisseleri filtreler.
     *
     * @param indexProname endeks proname degeri
     * @return filtrelenmis ve degistirilemez hisse listesi
     */
    private List<SectorStockDto> filterByIndex(String indexProname) {
        List<SectorStockDto> filtered = new ArrayList<>();
        for (StockWithIndexes stock : cachedAllStocks) {
            if (stock.indexPronames.contains(indexProname)) {
                filtered.add(stock.dto);
            }
        }
        log.info("[BIST-INDEX] {} endeksi: {} hisse filtrelendi", indexProname, filtered.size());
        return Collections.unmodifiableList(filtered);
    }

    /**
     * TradingView Scanner API'sine HTTP POST istegi gondererek
     * tum BIST hisselerini indexes column'u ile birlikte ceker.
     *
     * @return parse edilmis hisse listesi; hata durumunda bos liste
     */
    private List<StockWithIndexes> fetchAllStocks() {
        try {
            String requestBody = buildScanBody();

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(screenerUrl))
                    .timeout(Duration.ofSeconds(tradingViewConfig.getHttpRequestTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Origin", tradingViewConfig.getHeadersOrigin())
                    .header("Referer", tradingViewConfig.getHeadersReferer())
                    .header("User-Agent", TradingViewConfig.DEFAULT_USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody));

            String cookie = tradingViewConfig.getScreenerCookie();
            if (cookie != null && !cookie.isBlank()) {
                requestBuilder.header("Cookie", cookie);
            }

            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
                log.error("[BIST-INDEX] TradingView Scanner API hatasi: status={}", response.statusCode());
                return List.of();
            }

            return parseResponse(response.body());
        } catch (Exception e) {
            log.error("[BIST-INDEX] TradingView Scanner API cagrisi basarisiz", e);
            return List.of();
        }
    }

    /**
     * Tum hisse senetleri + indexes column'u iceren scan body JSON'unu olusturur.
     *
     * @return JSON formatinda scan body string
     */
    private String buildScanBody() {
        return """
                {
                  "columns": ["name", "description", "logoid", "close", "change", "volume", "indexes", "open"],
                  "ignore_unknown_fields": false,
                  "options": {"lang": "tr"},
                  "range": [0, 900],
                  "sort": {"sortBy": "name", "sortOrder": "asc"},
                  "symbols": {},
                  "markets": ["turkey"]
                }
                """;
    }

    /**
     * TradingView Scanner API response'unu parse ederek {@link StockWithIndexes} listesine donusturur.
     *
     * <p>Kolon sirasi: name(d[0]), description(d[1]), logoid(d[2]), close(d[3]),
     * change(d[4]), volume(d[5]), indexes(d[6]), open(d[7]).</p>
     *
     * @param responseBody API'den donen JSON response
     * @return parse edilmis hisse listesi (endeks bilgisi dahil)
     */
    private List<StockWithIndexes> parseResponse(String responseBody) {
        List<StockWithIndexes> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode dataArray = root.get("data");
            if (dataArray == null || !dataArray.isArray()) {
                return result;
            }

            for (int i = 0; i < dataArray.size(); i++) {
                JsonNode item = dataArray.get(i);
                if (item == null) continue;

                JsonNode dArray = item.get("d");
                if (dArray == null || !dArray.isArray()) continue;

                // ticker: "s" field -> "BIST:GARAN" -> "GARAN"
                String ticker = "N/A";
                JsonNode sNode = item.get("s");
                if (sNode != null) {
                    ticker = sNode.asText().replace("BIST:", "");
                }

                String description = dArray.size() > 1 && !dArray.get(1).isNull()
                        ? dArray.get(1).asText() : "";

                double price = dArray.size() > 3 && dArray.get(3).isNumber()
                        ? dArray.get(3).asDouble() : 0.0;

                double changePercent = dArray.size() > 4 && dArray.get(4).isNumber()
                        ? dArray.get(4).asDouble() : 0.0;

                double volume = dArray.size() > 5 && dArray.get(5).isNumber()
                        ? dArray.get(5).asDouble() : 0.0;

                // d[2] = logoid
                String logoid = dArray.size() > 2 && !dArray.get(2).isNull()
                        ? dArray.get(2).asText() : null;

                // d[6] = indexes array -> proname listesi
                List<String> indexPronames = extractIndexPronames(dArray);

                // d[7] = open (gunluk acilis fiyati)
                double open = dArray.size() > 7 && dArray.get(7).isNumber()
                        ? dArray.get(7).asDouble() : 0.0;

                SectorStockDto dto = new SectorStockDto(ticker, description, price, changePercent, volume, logoid, open,
                        katilimEndeksiService.isKatilim(ticker));
                result.add(new StockWithIndexes(dto, indexPronames));
            }
        } catch (Exception e) {
            log.error("[BIST-INDEX] Response parse hatasi", e);
        }
        return result;
    }

    /**
     * Hissenin uye oldugu endeks proname'lerini cikarir.
     *
     * @param dArray TradingView data array ({@code d})
     * @return endeks proname listesi (orn. ["BIST:XU100", "BIST:XU030"])
     */
    private List<String> extractIndexPronames(JsonNode dArray) {
        if (dArray.size() <= 6) return List.of();
        JsonNode indexesNode = dArray.get(6);
        if (indexesNode == null || !indexesNode.isArray()) return List.of();

        List<String> pronames = new ArrayList<>();
        for (JsonNode idx : indexesNode) {
            JsonNode proname = idx.get("proname");
            if (proname != null) {
                pronames.add(proname.asText());
            }
        }
        return pronames;
    }

    /**
     * Tum hisselerin logoid haritasini dondurur.
     *
     * <p>Mevcut volatile cache ({@code cachedAllStocks}) uzerinden calisir,
     * ekstra API cagrisi yapmaz. Cache bos veya null logoid'li hisseler
     * sonuc haritasina dahil edilmez.</p>
     *
     * @return ticker → logoid haritasi (orn. {"THYAO": "turk-hava-yollari"});
     *         cache henuz yuklenmemisse bos harita
     */
    public Map<String, String> getStockLogoidMap() {
        ensureCacheLoaded();
        if (cachedAllStocks == null) return Map.of();

        Map<String, String> local = cachedLogoidMap;
        if (local != null) return local;

        Map<String, String> logoMap = new HashMap<>();
        for (StockWithIndexes s : cachedAllStocks) {
            String logoid = s.dto.getLogoid();
            if (logoid != null && !logoid.isBlank()) {
                logoMap.put(s.dto.getTicker(), logoid);
            }
        }
        local = Collections.unmodifiableMap(logoMap);
        cachedLogoidMap = local;
        return local;
    }

    /**
     * Hisse verisi + endeks uyelik bilgisi iceren dahili veri yapisi.
     *
     * @param dto            hisse piyasa verileri
     * @param indexPronames  uye olunan endeks proname listesi
     */
    private record StockWithIndexes(SectorStockDto dto, List<String> indexPronames) {}
}
