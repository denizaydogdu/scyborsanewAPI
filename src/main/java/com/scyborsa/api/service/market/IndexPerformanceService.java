package com.scyborsa.api.service.market;

import com.scyborsa.api.config.TradingViewConfig;
import com.scyborsa.api.service.client.VelzonApiClient;
import com.scyborsa.api.utils.BistCacheUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.dto.market.IndexPerformanceDto;
import lombok.extern.slf4j.Slf4j;
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

/**
 * Borsa endeks performans verilerini hibrit veri kaynagi ile saglayan servis.
 *
 * <p><b>Hibrit yaklasim:</b></p>
 * <ul>
 *   <li><b>TradingView Scanner API (gercek zamanli):</b> {@code lastPrice} ve {@code dailyChange} —
 *       canli fiyat ve gunluk degisim yuzdesi</li>
 *   <li><b>Velzon API (sparkline):</b> {@code weeklyChange}, {@code monthlyChange},
 *       {@code quarterlyChange}, {@code sixMonthChange}, {@code yearlyChange} —
 *       sparkline grafikleri icin coklu zaman dilimi verileri</li>
 * </ul>
 *
 * <p>Velzon pinescreener formati: her endeks dogrudan {@code symbol}, {@code plot6},
 * {@code plot16} gibi key'lerle gelir.</p>
 *
 * <p>Plot mapping (Velzon):</p>
 * <ul>
 *   <li>{@code plot16} → lastPrice (close) — TradingView ile override edilir</li>
 *   <li>{@code plot6} → dailyChange% — TradingView ile override edilir</li>
 *   <li>{@code plot7} → weeklyChange%</li>
 *   <li>{@code plot8} → monthlyChange%</li>
 *   <li>{@code plot9} → quarterlyChange%</li>
 *   <li>{@code plot10} → sixMonthChange%</li>
 *   <li>{@code plot12} → yearlyChange%</li>
 * </ul>
 *
 * <p>TradingView Scanner istegi basarisiz olursa (graceful degradation),
 * Velzon verisi oldugu gibi kullanilir — davranis degismez.</p>
 *
 * <p>Sonuclar volatile cache ile saklanir. Dinamik TTL stratejisi: seans
 * saatlerinde (09:50-18:25) 60 saniye, seans disinda bir sonraki seans acilisina kadar
 * ({@link BistCacheUtils#getDynamicOffhoursTTL(long, long)}).</p>
 *
 * @see VelzonApiClient
 * @see TradingViewConfig
 * @see IndexPerformanceDto
 */
@Slf4j
@Service
public class IndexPerformanceService {

    /** Velzon API endpoint path'i. */
    private static final String INDEXES_PERFORMANCE_PATH = "/api/pinescreener/velzon_indexes_performance";

    /**
     * TradingView Scanner API'sine gonderilen istek govdesi.
     * <p>Tum BIST endekslerini explicit tickers listesi ile close ve change kolonlari ile ceker.</p>
     */
    private static final String TV_INDEX_SCAN_BODY = """
            {
              "symbols": {
                "tickers": [
                  "BIST:XU100","BIST:XU050","BIST:XU030",
                  "BIST:XBANK","BIST:XFINK","BIST:XUSIN","BIST:XHOLD","BIST:XGIDA",
                  "BIST:XMADN","BIST:XSGRT","BIST:XELKT","BIST:XILTM","BIST:XKMYA",
                  "BIST:XTRZM","BIST:XUMAL","BIST:XUTEK","BIST:XUHIZ","BIST:XINSA",
                  "BIST:XKTUM","BIST:XYORT","BIST:XGMYO","BIST:XMANA","BIST:XK050",
                  "BIST:XK030","BIST:XK100","BIST:XUTUM","BIST:XTCRT","BIST:XTEKS",
                  "BIST:XMESY","BIST:XBLSM","BIST:XKAGT","BIST:XTAST","BIST:XULAS",
                  "BIST:XSPOR","BIST:XAKUR"
                ]
              },
              "columns": ["name", "close", "change"]
            }
            """;

    /** Seans ici cache TTL (milisaniye): 60 saniye. */
    private static final long LIVE_TTL_MS = 60_000L;

    /** Seans disi minimum cache TTL (milisaniye): 30 dakika. Gercek TTL bir sonraki seans acilisina kadar hesaplanir. */
    private static final long MIN_OFFHOURS_TTL_MS = 1_800_000L;

    /** Velzon API istemcisi. */
    private final VelzonApiClient velzonApiClient;

    /** TradingView API konfigurasyonu (URL, cookie, header). */
    private final TradingViewConfig tradingViewConfig;

    /** JSON parse icin Jackson ObjectMapper. */
    private final ObjectMapper objectMapper;

    /** HTTP istekleri icin Java 11 HttpClient. */
    private final HttpClient httpClient;

    /** TradingView Scanner API URL'i. */
    private final String screenerUrl;

    /** Cache: endeks performans listesi. Volatile ile thread-safe erisim. */
    private volatile List<IndexPerformanceDto> cachedPerformances;

    /** Cache: son guncelleme zamani (epoch millis). */
    private volatile long cacheTimestamp;

    /** Cache yenileme icin kilit nesnesi. */
    private final Object cacheLock = new Object();

    /**
     * Constructor injection ile bagimliliklari alir ve HTTP client olusturur.
     *
     * @param velzonApiClient    Velzon API istemcisi (sparkline verileri icin)
     * @param tradingViewConfig  TradingView API konfigurasyonu (URL, cookie, header)
     * @param objectMapper       JSON parse icin Jackson ObjectMapper
     */
    public IndexPerformanceService(VelzonApiClient velzonApiClient,
                                   TradingViewConfig tradingViewConfig,
                                   ObjectMapper objectMapper) {
        this.velzonApiClient = velzonApiClient;
        this.tradingViewConfig = tradingViewConfig;
        this.objectMapper = objectMapper;
        this.screenerUrl = tradingViewConfig.getScreenerApiUrl();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(tradingViewConfig.getHttpConnectTimeoutSeconds()))
                .build();
    }

    /**
     * Tum BIST endekslerinin performans verilerini dondurur.
     *
     * <p>Velzon API'den {@code /api/pinescreener/velzon_indexes_performance}
     * endpoint'ini cagirarak endeks verilerini alir.</p>
     *
     * <p>Her endeks icin sembol ({@code symbol} alanindaki exchange prefix'i
     * cikartilir), son fiyat, gunluk/haftalik/aylik/ceyreklik/6 aylik/yillik
     * degisim yuzdesi bilgisi dondurulur.</p>
     *
     * <p>Sonuclar volatile cache ile saklanir. Dinamik TTL stratejisi:
     * seans saatlerinde (09:50-18:25) 60s, seans disinda bir sonraki seans acilisina kadar
     * ({@link BistCacheUtils#getDynamicOffhoursTTL(long, long)}). Cache suresi dolmadan
     * tekrar cagrilirsa cached deger dondurulur.</p>
     *
     * @return endeks performans listesi; hata durumunda bos liste
     */
    public List<IndexPerformanceDto> getIndexPerformances() {
        // Cache kontrolu (ilk kontrol — lock disinda)
        long ttl = BistCacheUtils.getDynamicOffhoursTTL(LIVE_TTL_MS, MIN_OFFHOURS_TTL_MS);
        long now = System.currentTimeMillis();
        if (cachedPerformances != null && (now - cacheTimestamp) < ttl) {
            log.debug("[INDEX-PERF] Cache hit, kalan sure: {}s",
                    (ttl - (now - cacheTimestamp)) / 1000);
            return cachedPerformances;
        }

        synchronized (cacheLock) {
            // Double-check: baska thread araya girip cache'i guncellemis olabilir
            ttl = BistCacheUtils.getDynamicOffhoursTTL(LIVE_TTL_MS, MIN_OFFHOURS_TTL_MS);
            now = System.currentTimeMillis();
            if (cachedPerformances != null && (now - cacheTimestamp) < ttl) {
                return cachedPerformances;
            }

            log.info("[INDEX-PERF] Endeks performans verileri Velzon API'den cekiliyor (TTL: {}ms)", ttl);

            try {
                Map<String, Object> response = velzonApiClient.get(
                        INDEXES_PERFORMANCE_PATH,
                        new TypeReference<Map<String, Object>>() {}
                );

                // Data dizisini parse et
                Object rawData = response.get("data");
                if (!(rawData instanceof List)) {
                    log.warn("[INDEX-PERF] Data alani beklenmeyen formatta: {}",
                            rawData != null ? rawData.getClass().getSimpleName() : "null");
                    return cachedPerformances != null ? cachedPerformances : List.of();
                }

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> data = (List<Map<String, Object>>) rawData;

                List<IndexPerformanceDto> result = new ArrayList<>();

                for (Map<String, Object> item : data) {
                    IndexPerformanceDto dto = parseItem(item);
                    if (dto != null) {
                        result.add(dto);
                    }
                }

                // Hibrit: TradingView Scanner'dan gercek zamanli lastPrice + dailyChange al
                Map<String, double[]> realtimePrices = fetchRealtimePrices();
                if (!realtimePrices.isEmpty()) {
                    int overrideCount = 0;
                    for (IndexPerformanceDto dto : result) {
                        double[] tvData = realtimePrices.get(dto.getSymbol());
                        if (tvData != null) {
                            dto.setLastPrice(tvData[0]);
                            dto.setDailyChange(tvData[1]);
                            overrideCount++;
                        }
                    }
                    log.info("[INDEX-PERF] TradingView gercek zamanli veri ile {}/{} endeks override edildi",
                            overrideCount, result.size());
                }

                // Cache guncelle
                this.cachedPerformances = Collections.unmodifiableList(result);
                this.cacheTimestamp = System.currentTimeMillis();

                log.info("[INDEX-PERF] {} endeks performans verisi basariyla yuklendi (hibrit)", result.size());

                return cachedPerformances;

            } catch (Exception e) {
                log.error("[INDEX-PERF] Endeks performans verisi alinamadi: {}", e.getMessage(), e);
                return cachedPerformances != null ? cachedPerformances : List.of();
            }
        }
    }

    /**
     * TradingView Scanner API'sinden BIST endekslerinin gercek zamanli fiyat ve gunluk degisim verilerini ceker.
     *
     * <p>HTTP POST istegi ile {@code scanner.tradingview.com/turkey/scan} endpoint'ine
     * endeks tarama istegi gonderir. Sonuc olarak her endeks icin {@code close} ve
     * {@code change} degerlerini dondurur.</p>
     *
     * <p>Graceful degradation: herhangi bir hata durumunda bos {@link Map} dondurur,
     * boylece Velzon verisi oldugu gibi kullanilir.</p>
     *
     * @return sembol → [close, change%] haritasi; hata durumunda bos harita
     */
    private Map<String, double[]> fetchRealtimePrices() {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(screenerUrl))
                    .timeout(Duration.ofSeconds(tradingViewConfig.getHttpRequestTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Origin", tradingViewConfig.getHeadersOrigin())
                    .header("Referer", tradingViewConfig.getHeadersReferer())
                    .header("User-Agent", TradingViewConfig.DEFAULT_USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofString(TV_INDEX_SCAN_BODY));

            String cookie = tradingViewConfig.getScreenerCookie();
            if (cookie != null && !cookie.isBlank()) {
                requestBuilder.header("Cookie", cookie);
            }

            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
                log.warn("[INDEX-PERF] TradingView Scanner API hatasi: status={}", response.statusCode());
                return Map.of();
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode dataArray = root.get("data");
            if (dataArray == null || !dataArray.isArray()) {
                log.warn("[INDEX-PERF] TradingView yaniti beklenen formatta degil");
                return Map.of();
            }

            Map<String, double[]> result = new HashMap<>();
            for (JsonNode item : dataArray) {
                // "s" alani: "BIST:XU100" formatinda sembol
                String rawSymbol = item.has("s") ? item.get("s").asText() : null;
                if (rawSymbol == null) continue;

                String symbol = extractSymbol(rawSymbol);
                if (symbol == null) continue;

                // "d" alani: [name, close, change] dizisi
                JsonNode dArray = item.get("d");
                if (dArray == null || !dArray.isArray() || dArray.size() < 3) continue;

                // d[0]=name (string), d[1]=close (double), d[2]=change% (double)
                double close = dArray.get(1).isNumber() ? dArray.get(1).asDouble() : 0.0;
                double change = dArray.get(2).isNumber() ? dArray.get(2).asDouble() : 0.0;

                if (close > 0) {
                    result.put(symbol, new double[]{close, change});
                }
            }

            log.info("[INDEX-PERF] TradingView Scanner'dan {} endeks gercek zamanli verisi alindi", result.size());
            return result;

        } catch (Exception e) {
            log.warn("[INDEX-PERF] TradingView gercek zamanli veri alinamadi (graceful degradation): {}",
                    e.getMessage());
            return Map.of();
        }
    }

    /**
     * Tek bir data satirini parse ederek {@link IndexPerformanceDto} olusturur.
     *
     * <p>Velzon pinescreener formati: her item dogrudan {@code symbol} ve
     * {@code plotN} key'leri ile gelir.</p>
     *
     * @param item tek bir veri satiri (data dizisinin elemani)
     * @return parse edilmis DTO; gecersiz veri durumunda {@code null}
     */
    private IndexPerformanceDto parseItem(Map<String, Object> item) {
        // Symbol: "BIST:XU100" → "XU100"
        String symbol = extractSymbol(item.get("symbol"));
        if (symbol == null) {
            return null;
        }

        return IndexPerformanceDto.builder()
                .symbol(symbol)
                .lastPrice(getPlotValue(item, "plot16"))
                .dailyChange(getPlotValue(item, "plot6"))
                .weeklyChange(getPlotValue(item, "plot7"))
                .monthlyChange(getPlotValue(item, "plot8"))
                .quarterlyChange(getPlotValue(item, "plot9"))
                .sixMonthChange(getPlotValue(item, "plot10"))
                .yearlyChange(getPlotValue(item, "plot12"))
                .build();
    }

    /**
     * Sembol degerini parse eder ve exchange prefix'ini cikarir.
     *
     * <p>"BIST:XFINK" → "XFINK", "FX:EURTRY" → "EURTRY" seklinde
     * iki noktadan sonrasini alir.</p>
     *
     * @param raw symbol ham degeri
     * @return temizlenmis sembol (orn. "XU100"); gecersiz veri durumunda {@code null}
     */
    private String extractSymbol(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.toString();
        int colonIdx = value.indexOf(':');
        if (colonIdx >= 0 && colonIdx < value.length() - 1) {
            return value.substring(colonIdx + 1);
        }
        return value;
    }

    /**
     * Belirtilen plot key'indeki degeri double olarak okur.
     *
     * <p>Key bulunamazsa veya deger numerik degilse 0.0 dondurur.</p>
     *
     * @param item data satiri
     * @param plotKey plot key'i (orn. "plot16")
     * @return double deger; okunamazsa 0.0
     */
    private double getPlotValue(Map<String, Object> item, String plotKey) {
        Object value = item.get(plotKey);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 0.0;
    }
}
