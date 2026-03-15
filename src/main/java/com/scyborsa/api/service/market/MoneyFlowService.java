package com.scyborsa.api.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.config.TradingViewConfig;
import com.scyborsa.api.dto.market.MoneyFlowResponse;
import com.scyborsa.api.dto.market.MoneyFlowStockDto;
import com.scyborsa.api.service.telegram.TelegramVolumeFormatter;
import com.scyborsa.api.utils.BistCacheUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * BIST hisselerinin para akisi (money flow) verilerini saglayan servis.
 *
 * <p>TradingView Scanner API'sine HTTP POST istegi gondererek en yuksek hacimli
 * hisseleri ceker. Pozitif degisimli hisseler para girisi, negatif degisimli
 * hisseler para cikisi olarak siniflandirilir. Her iki liste ciroya gore
 * azalan sirada siralanir ve ilk 5 hisse dondurulur.</p>
 *
 * <p>Volatile cache + double-check locking ile thread-safe cache yonetimi.
 * Cache TTL'i {@link BistCacheUtils#getAdaptiveTTL(long, long)} ile seans
 * durumuna gore uyarlanir (seans ici: 60s, seans disi: 1 saat).</p>
 *
 * @see MoneyFlowResponse
 * @see MoneyFlowStockDto
 * @see TradingViewConfig
 */
@Slf4j
@Service
public class MoneyFlowService {

    /** TradingView API konfigurasyonu (URL, cookie, header). */
    private final TradingViewConfig tradingViewConfig;

    /** JSON parse icin Jackson ObjectMapper. */
    private final ObjectMapper objectMapper;

    /** HTTP istekleri icin Java 11 HttpClient. */
    private final HttpClient httpClient;

    /** TradingView Scanner API URL'i. */
    private final String screenerUrl;

    /** Cache: son hesaplanan para akisi yaniti. */
    private volatile MoneyFlowResponse cached;

    /** Cache: son guncelleme zamani (epoch millis). */
    private volatile long cacheTs;

    /** Cache yenileme kilit nesnesi. */
    private final Object cacheLock = new Object();

    /**
     * Constructor injection ile bagimliliklari alir ve HTTP client olusturur.
     *
     * @param tradingViewConfig TradingView API konfigurasyonu (URL, cookie, header)
     * @param objectMapper      JSON parse icin Jackson ObjectMapper
     */
    public MoneyFlowService(TradingViewConfig tradingViewConfig,
                            ObjectMapper objectMapper) {
        this.tradingViewConfig = tradingViewConfig;
        this.objectMapper = objectMapper;
        this.screenerUrl = tradingViewConfig.getScreenerApiUrl();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(tradingViewConfig.getHttpConnectTimeoutSeconds()))
                .build();
    }

    /**
     * Para akisi verilerini dondurur (inflow + outflow).
     *
     * <p>Cache gecerliyse dogrudan cache'den doner. Cache suresi dolmussa
     * TradingView Scanner API'sinden guncel veri ceker.</p>
     *
     * @return para girisi ve cikisi hisse listelerini iceren {@link MoneyFlowResponse};
     *         hata durumunda bos listeler
     */
    public MoneyFlowResponse getMoneyFlow() {
        ensureCacheLoaded();
        MoneyFlowResponse local = cached;
        if (local != null) {
            return local;
        }
        return new MoneyFlowResponse(List.of(), List.of());
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
        long ttl = BistCacheUtils.getAdaptiveTTL(60_000, 3_600_000);
        if (cached != null && (now - cacheTs) < ttl) {
            return;
        }

        synchronized (cacheLock) {
            now = System.currentTimeMillis();
            if (cached != null && (now - cacheTs) < ttl) {
                return;
            }

            log.info("[MONEY-FLOW] Para akisi verileri guncelleniyor");
            MoneyFlowResponse result = fetchAndCompute();

            if (result == null) {
                log.warn("[MONEY-FLOW] TradingView taramasi bos sonuc dondurdu");
                this.cacheTs = System.currentTimeMillis(); // Backoff: thundering herd onlemi
                return;
            }

            this.cached = result;
            this.cacheTs = System.currentTimeMillis();

            log.info("[MONEY-FLOW] Para akisi guncellendi: {} giris, {} cikis",
                    result.getInflow().size(), result.getOutflow().size());
        }
    }

    /**
     * TradingView Scanner API'sinden veri ceker, parse eder ve para akisi hesaplar.
     *
     * @return hesaplanan para akisi yaniti; hata durumunda {@code null}
     */
    private MoneyFlowResponse fetchAndCompute() {
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
                log.error("[MONEY-FLOW] TradingView Scanner API hatasi: status={}, body={}",
                        response.statusCode(), response.body());
                return null;
            }

            return parseAndSplit(response.body());
        } catch (Exception e) {
            log.error("[MONEY-FLOW] TradingView Scanner API cagrisi basarisiz", e);
            return null;
        }
    }

    /**
     * Hacme gore azalan sirada ilk 200 BIST hissesini ceken scan body JSON'unu olusturur.
     *
     * <p>TradingView Scanner API'sinde {@code Value.Traded} columns/sort'ta desteklenmez
     * (400 hatasi), bu yuzden {@code volume} sort kullanilir. Yuksek fiyatli dusuk lotlu
     * hisselerin kacmamasini onlemek icin range 200'e cikartilmistir.
     * Java tarafinda {@code volume x close} ile TL ciro hesaplanir.</p>
     *
     * @return JSON formatinda scan body string
     */
    private String buildScanBody() {
        return """
                {
                  "columns": ["name", "description", "logoid", "close", "change", "volume"],
                  "filter": [],
                  "ignore_unknown_fields": false,
                  "options": {"lang": "tr"},
                  "range": [0, 200],
                  "sort": {"sortBy": "volume", "sortOrder": "desc"},
                  "symbols": {},
                  "markets": ["turkey"],
                  "filter2": {
                    "operator": "and",
                    "operands": [
                      {
                        "operation": {
                          "operator": "or",
                          "operands": [
                            {"operation": {"operator": "and", "operands": [{"expression": {"left": "type", "operation": "equal", "right": "stock"}}, {"expression": {"left": "typespecs", "operation": "has", "right": ["common"]}}]}},
                            {"operation": {"operator": "and", "operands": [{"expression": {"left": "type", "operation": "equal", "right": "stock"}}, {"expression": {"left": "typespecs", "operation": "has", "right": ["preferred"]}}]}}
                          ]
                        }
                      },
                      {"expression": {"left": "typespecs", "operation": "has_none_of", "right": ["pre-ipo"]}}
                    ]
                  }
                }
                """;
    }

    /**
     * TradingView Scanner API response'unu parse ederek para girisi/cikisi listelerine ayirir.
     *
     * <p>Kolon sirasi: name(d[0]), description(d[1]), logoid(d[2]), close(d[3]),
     * change(d[4]), volume(d[5]). Ciro = volume x close. Pozitif degisim → inflow,
     * negatif degisim → outflow. Her liste ciroya gore azalan sirada ilk 5.</p>
     *
     * @param responseBody API'den donen JSON response
     * @return parse edilmis para akisi yaniti; hata durumunda {@code null}
     */
    private MoneyFlowResponse parseAndSplit(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode dataArray = root.get("data");
            if (dataArray == null || !dataArray.isArray()) {
                return null;
            }

            List<MoneyFlowStockDto> inflow = new ArrayList<>();
            List<MoneyFlowStockDto> outflow = new ArrayList<>();

            for (int i = 0; i < dataArray.size(); i++) {
                JsonNode item = dataArray.get(i);
                if (item == null) continue;

                JsonNode dArray = item.get("d");
                if (dArray == null || !dArray.isArray()) continue;

                // d[0] = name ("BIST:GARAN" -> "GARAN")
                String name = dArray.size() > 0 && !dArray.get(0).isNull()
                        ? dArray.get(0).asText() : "";
                String ticker = name.replace("BIST:", "");

                // d[1] = description
                String description = dArray.size() > 1 && !dArray.get(1).isNull()
                        ? dArray.get(1).asText() : "";

                // d[2] = logoid (TradingView ~12 hissede bos string doner, null degil)
                String logoid = dArray.size() > 2 && !dArray.get(2).isNull()
                        ? dArray.get(2).asText() : null;
                if (logoid != null && logoid.isEmpty()) logoid = null;

                // d[3] = close
                double close = dArray.size() > 3 && dArray.get(3).isNumber()
                        ? dArray.get(3).asDouble() : 0.0;

                // d[4] = change (yuzde degisim)
                double change = dArray.size() > 4 && dArray.get(4).isNumber()
                        ? dArray.get(4).asDouble() : 0.0;

                // d[5] = volume (lot sayisi)
                double volume = dArray.size() > 5 && dArray.get(5).isNumber()
                        ? dArray.get(5).asDouble() : 0.0;

                // Ciro hesaplama: volume x close = TL ciro
                double turnoverTL = volume * close;

                // Sifir degisimli hisseleri atla
                if (change == 0.0) continue;

                MoneyFlowStockDto dto = MoneyFlowStockDto.builder()
                        .ticker(ticker)
                        .description(description)
                        .logoid(logoid)
                        .price(close)
                        .changePercent(change)
                        .turnoverTL(turnoverTL)
                        .turnoverFormatted(TelegramVolumeFormatter.formatVolumeTurkish(turnoverTL))
                        .build();

                if (change > 0) {
                    inflow.add(dto);
                } else {
                    outflow.add(dto);
                }
            }

            // Ciroya gore azalan sirala ve ilk 5
            inflow.sort(Comparator.comparingDouble(MoneyFlowStockDto::getTurnoverTL).reversed());
            outflow.sort(Comparator.comparingDouble(MoneyFlowStockDto::getTurnoverTL).reversed());

            List<MoneyFlowStockDto> topInflow = inflow.size() > 5 ? inflow.subList(0, 5) : inflow;
            List<MoneyFlowStockDto> topOutflow = outflow.size() > 5 ? outflow.subList(0, 5) : outflow;

            return new MoneyFlowResponse(
                    List.copyOf(topInflow),
                    List.copyOf(topOutflow)
            );
        } catch (Exception e) {
            log.error("[MONEY-FLOW] Response parse hatasi", e);
            return null;
        }
    }
}
