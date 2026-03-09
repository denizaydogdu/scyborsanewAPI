package com.scyborsa.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.config.SectorDefinitionRegistry;
import com.scyborsa.api.config.TradingViewConfig;
import com.scyborsa.api.dto.SectorDefinitionDto;
import com.scyborsa.api.dto.SectorStockDto;
import com.scyborsa.api.dto.SectorSummaryDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Sektor bazli hisse taramasi ve ozet hesaplama servisi.
 *
 * <p>TradingView Scanner API'sine dogrudan HTTP POST istegi gondererek
 * sektor hisse listelerini ve ozet istatistiklerini uretir.
 * Sektor tanimlari {@link SectorDefinitionRegistry} uzerinden JSON tabanli yuklenir.</p>
 *
 * <p>Iki temel islem sunar:</p>
 * <ul>
 *   <li><b>Ozet (summaries):</b> Tek bir scan ile tum Turkiye hisselerini ceker,
 *       her hisseyi ilgili sektorlere esleyerek sektor bazli ozet istatistik uretir.
 *       Sonuclar volatile cache ile saklanir (varsayilan TTL: 120 saniye).</li>
 *   <li><b>Detay (sector stocks):</b> Belirli bir sektor icin dinamik scan body
 *       olusturarak hisse listesini dondurur.</li>
 * </ul>
 *
 * <p>Bagimliliklar:</p>
 * <ul>
 *   <li>{@link TradingViewConfig} - API URL, cookie ve header konfigurasyonu</li>
 *   <li>{@link SectorDefinitionRegistry} - JSON tabanli sektor tanimlari</li>
 *   <li>{@link ObjectMapper} - JSON parse islemi</li>
 * </ul>
 *
 * @see SectorDefinitionRegistry
 * @see SectorDefinitionDto
 * @see SectorStockDto
 * @see SectorSummaryDto
 */
@Slf4j
@Service
public class SectorService {

    private final TradingViewConfig tradingViewConfig;
    private final ObjectMapper objectMapper;
    private final SectorDefinitionRegistry registry;
    private final HttpClient httpClient;
    private final String screenerUrl;

    /** Cache: sektor ozet listesi. Volatile ile thread-safe erisim. */
    private volatile List<SectorSummaryDto> cachedSummaries;

    /** Cache: son guncelleme zamani (epoch millis). */
    private volatile long cacheTimestamp;

    /** Cache TTL (saniye cinsinden). */
    @Value("${sector.cache.ttl-seconds:120}")
    private int cacheTtlSeconds;

    /**
     * Constructor injection ile bagimliliklari alir ve HTTP client olusturur.
     *
     * @param tradingViewConfig TradingView API konfigurasyonu (URL, cookie, header)
     * @param objectMapper      JSON parse icin Jackson ObjectMapper
     * @param registry          sektor tanim registry'si
     */
    public SectorService(TradingViewConfig tradingViewConfig,
                         ObjectMapper objectMapper,
                         SectorDefinitionRegistry registry) {
        this.tradingViewConfig = tradingViewConfig;
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.screenerUrl = tradingViewConfig.getScreenerApiUrl();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(tradingViewConfig.getHttpConnectTimeoutSeconds()))
                .build();
    }

    /**
     * Tum sektorlerin ozet istatistiklerini dondurur.
     *
     * <p>Tek bir TradingView scan ile tum Turkiye hisselerini ceker,
     * her hisseyi sector/industry/ticker bilgisine gore ilgili sektorlere esler
     * ve sektor bazli hisse sayisi + ortalama degisim yuzdesi hesaplar.</p>
     *
     * <p>Sonuclar volatile cache ile saklanir. Cache TTL dolmadan
     * tekrar cagrilirsa cached deger dondurulur.</p>
     *
     * @return sektor ozet listesi (ortalama degisim yuzdesine gore azalan sirali);
     *         hata durumunda bos liste
     */
    public List<SectorSummaryDto> getSectorSummaries() {
        // Cache kontrolu
        long now = System.currentTimeMillis();
        if (cachedSummaries != null && (now - cacheTimestamp) < (long) cacheTtlSeconds * 1000) {
            log.debug("[SECTOR-SERVICE] Cache hit, kalan sure: {}s",
                    cacheTtlSeconds - (now - cacheTimestamp) / 1000);
            return cachedSummaries;
        }

        log.info("[SECTOR-SERVICE] Sektor ozetleri hesaplaniyor (tum Turkiye hisseleri taranacak)");

        String allStocksBody = buildAllStocksScanBody();
        List<RawStockData> allStocks = scanAllStocks(allStocksBody);

        if (allStocks.isEmpty()) {
            log.warn("[SECTOR-SERVICE] Tum hisse taramasi bos sonuc dondurdu");
            return cachedSummaries != null ? cachedSummaries : List.of();
        }

        // Her hisseyi sektorlere esle
        Map<String, List<Double>> sectorChanges = new HashMap<>();

        for (RawStockData stock : allStocks) {
            List<SectorDefinitionDto> matchedSectors = registry.matchStock(
                    stock.tvSector, stock.tvIndustry, stock.ticker);

            for (SectorDefinitionDto sector : matchedSectors) {
                sectorChanges.computeIfAbsent(sector.getSlug(), k -> new ArrayList<>())
                        .add(stock.changePercent);
            }
        }

        // Ozet DTO'lari olustur
        List<SectorSummaryDto> summaries = new ArrayList<>();
        for (SectorDefinitionDto def : registry.getAll()) {
            List<Double> changes = sectorChanges.get(def.getSlug());
            int stockCount = changes != null ? changes.size() : 0;
            double avgChange = 0.0;
            if (changes != null && !changes.isEmpty()) {
                avgChange = changes.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            }

            summaries.add(SectorSummaryDto.builder()
                    .slug(def.getSlug())
                    .displayName(def.getDisplayName())
                    .description(def.getDescription())
                    .icon(def.getIcon())
                    .stockCount(stockCount)
                    .avgChangePercent(Math.round(avgChange * 100.0) / 100.0)
                    .build());
        }

        // Ortalama degisim yuzdesine gore azalan sirala
        summaries.sort(Comparator.comparingDouble(SectorSummaryDto::getAvgChangePercent).reversed());

        // Cache guncelle
        this.cachedSummaries = Collections.unmodifiableList(summaries);
        this.cacheTimestamp = System.currentTimeMillis();

        log.info("[SECTOR-SERVICE] {} sektor ozeti hesaplandi ({} hisse taranildi)",
                summaries.size(), allStocks.size());

        return cachedSummaries;
    }

    /**
     * Belirtilen sektore ait hisse listesini TradingView Scanner API'den ceker.
     *
     * <p>Sektor tanimina gore dinamik scan body olusturur:</p>
     * <ul>
     *   <li>Ticker tabanli sektorler: {@code name in_range} filtresi</li>
     *   <li>Sector + industry tabanli sektorler: {@code sector + industry} filtresi</li>
     *   <li>Sadece sector tabanli sektorler: {@code sector} filtresi</li>
     * </ul>
     *
     * @param slug sektor slug degeri (orn. "bankacilik")
     * @return sektor hisse listesi; gecersiz slug veya hata durumunda bos liste
     */
    public List<SectorStockDto> getSectorStocks(String slug) {
        SectorDefinitionDto def = registry.getBySlug(slug);
        if (def == null) {
            log.warn("[SECTOR-SERVICE] Bilinmeyen sektor slug: {}", slug);
            return List.of();
        }

        String body = buildScanBody(def);
        return scan(body);
    }

    /**
     * Tum Turkiye hisselerini sector ve industry bilgisiyle birlikte tarayan
     * scan body JSON'unu olusturur.
     *
     * @return JSON formatinda scan body string
     */
    private String buildAllStocksScanBody() {
        return """
                {
                  "columns": ["name", "description", "logoid", "close", "change", "volume", "sector", "industry"],
                  "ignore_unknown_fields": false,
                  "options": {"lang": "tr"},
                  "range": [0, 700],
                  "sort": {"sortBy": "volume", "sortOrder": "desc"},
                  "symbols": {},
                  "markets": ["turkey"],
                  "filter2": {
                    "operator": "and",
                    "operands": [
                      {
                        "operation": {
                          "operator": "and",
                          "operands": [
                            {"expression": {"left": "type", "operation": "equal", "right": "stock"}},
                            {"expression": {"left": "typespecs", "operation": "has", "right": ["common"]}}
                          ]
                        }
                      }
                    ]
                  }
                }
                """;
    }

    /**
     * Sektor tanimina gore dinamik scan body JSON'u olusturur.
     *
     * <p>Uc farkli filtre stratejisi uygulanir:</p>
     * <ol>
     *   <li>Ticker tabanli: {@code name in_range ["BIST:TICKER1", ...]} filtresi</li>
     *   <li>Sector + industry: sector + industry filtreleri</li>
     *   <li>Sadece sector: yalnizca sector filtresi (tum industry'ler dahil)</li>
     * </ol>
     *
     * @param def sektor tanimi
     * @return JSON formatinda scan body string
     */
    private String buildScanBody(SectorDefinitionDto def) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"columns\":[\"name\",\"description\",\"logoid\",\"close\",\"change\",\"volume\"],");
        sb.append("\"ignore_unknown_fields\":false,");
        sb.append("\"options\":{\"lang\":\"tr\"},");
        sb.append("\"range\":[0,150],");
        sb.append("\"sort\":{\"sortBy\":\"change\",\"sortOrder\":\"desc\"},");

        if (def.getTickers() != null && !def.getTickers().isEmpty()) {
            // Ticker tabanli: symbols.tickers alanini kullan (filter2 degil)
            sb.append("\"symbols\":{\"tickers\":[");
            StringJoiner tickerJoiner = new StringJoiner(",");
            for (String ticker : def.getTickers()) {
                tickerJoiner.add("\"BIST:" + ticker + "\"");
            }
            sb.append(tickerJoiner);
            sb.append("]},");
            sb.append("\"markets\":[\"turkey\"]");
        } else {
            // Sector/industry tabanli: filter2 kullan
            sb.append("\"symbols\":{},");
            sb.append("\"markets\":[\"turkey\"],");
            sb.append("\"filter2\":{\"operator\":\"and\",\"operands\":[");

            // Base type filter (stock + common)
            sb.append("{\"operation\":{\"operator\":\"and\",\"operands\":[");
            sb.append("{\"expression\":{\"left\":\"type\",\"operation\":\"equal\",\"right\":\"stock\"}},");
            sb.append("{\"expression\":{\"left\":\"typespecs\",\"operation\":\"has\",\"right\":[\"common\"]}}");
            sb.append("]}}");

            if (def.getTvSector() != null) {
                // Sector filtresi
                sb.append(",{\"expression\":{\"left\":\"sector\",\"operation\":\"equal\",\"right\":\"");
                sb.append(escapeJson(def.getTvSector()));
                sb.append("\"}}");

                // Industry filtresi (varsa)
                if (def.getTvIndustries() != null && !def.getTvIndustries().isEmpty()) {
                    sb.append(",{\"expression\":{\"left\":\"industry\",\"operation\":\"in_range\",\"right\":[");
                    StringJoiner industryJoiner = new StringJoiner(",");
                    for (String industry : def.getTvIndustries()) {
                        industryJoiner.add("\"" + escapeJson(industry) + "\"");
                    }
                    sb.append(industryJoiner);
                    sb.append("]}}");
                }
            }

            sb.append("]}");
        }

        sb.append("}");
        return sb.toString();
    }

    /**
     * JSON string icin ozel karakterleri escape eder.
     *
     * @param value escape edilecek string
     * @return escape edilmis string
     */
    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * TradingView Scanner API'sine HTTP POST istegi gonderir ve hisse listesi dondurur.
     *
     * @param requestBody JSON formatinda scan body
     * @return parse edilmis hisse listesi; hata durumunda bos liste
     */
    private List<SectorStockDto> scan(String requestBody) {
        try {
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
                log.error("[SECTOR-SERVICE] TradingView Scanner API hatasi: status={}", response.statusCode());
                return List.of();
            }

            return parseResponse(response.body());
        } catch (Exception e) {
            log.error("[SECTOR-SERVICE] TradingView Scanner API cagrisi basarisiz", e);
            return List.of();
        }
    }

    /**
     * Tum hisse taramasi icin TradingView Scanner API'sine HTTP POST istegi gonderir.
     *
     * <p>Sector ve industry bilgisi iceren genisletilmis kolon seti ile tarama yapar.</p>
     *
     * @param requestBody JSON formatinda scan body (sector/industry kolonlari dahil)
     * @return ham hisse verisi listesi; hata durumunda bos liste
     */
    private List<RawStockData> scanAllStocks(String requestBody) {
        try {
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
                log.error("[SECTOR-SERVICE] Tum hisse taramasi basarisiz: status={}", response.statusCode());
                return List.of();
            }

            return parseSummaryResponse(response.body());
        } catch (Exception e) {
            log.error("[SECTOR-SERVICE] Tum hisse taramasi cagrisi basarisiz", e);
            return List.of();
        }
    }

    /**
     * TradingView Scanner API response'unu parse ederek {@link SectorStockDto} listesine donusturur.
     *
     * <p>Kolon sirasi: name(d[0]), description(d[1]), logoid(d[2]), close(d[3]),
     * change(d[4]), volume(d[5]). Ticker degeri {@code s} alanindaki
     * "BIST:" prefix'i kaldirilarak elde edilir.</p>
     *
     * @param responseBody API'den donen JSON response
     * @return parse edilmis hisse listesi
     */
    private List<SectorStockDto> parseResponse(String responseBody) {
        List<SectorStockDto> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode dataArray = root.get("data");
            if (dataArray == null || !dataArray.isArray()) {
                return result;
            }

            for (int i = 0; i < dataArray.size(); i++) {
                JsonNode item = dataArray.get(i);
                if (item == null) continue;

                // ticker: "s" field -> "BIST:GARAN" -> "GARAN"
                String ticker = "N/A";
                JsonNode sNode = item.get("s");
                if (sNode != null) {
                    ticker = sNode.asText().replace("BIST:", "");
                }

                JsonNode dArray = item.get("d");
                if (dArray == null || !dArray.isArray()) continue;

                // d[1] = description
                String description = dArray.size() > 1 && !dArray.get(1).isNull()
                        ? dArray.get(1).asText() : "";

                // d[3] = close (price)
                double price = dArray.size() > 3 && dArray.get(3).isNumber()
                        ? dArray.get(3).asDouble() : 0.0;

                // d[4] = change (changePercent)
                double changePercent = dArray.size() > 4 && dArray.get(4).isNumber()
                        ? dArray.get(4).asDouble() : 0.0;

                // d[5] = volume
                double volume = dArray.size() > 5 && dArray.get(5).isNumber()
                        ? dArray.get(5).asDouble() : 0.0;

                // d[2] = logoid
                String logoid = dArray.size() > 2 && !dArray.get(2).isNull()
                        ? dArray.get(2).asText() : null;

                result.add(new SectorStockDto(ticker, description, price, changePercent, volume, logoid));
            }
        } catch (Exception e) {
            log.error("[SECTOR-SERVICE] Response parse hatasi", e);
        }
        return result;
    }

    /**
     * Tum hisse taramasi response'unu parse ederek sector/industry bilgisi dahil
     * ham hisse verisi listesine donusturur.
     *
     * <p>Kolon sirasi: name(d[0]), description(d[1]), logoid(d[2]), close(d[3]),
     * change(d[4]), volume(d[5]), sector(d[6]), industry(d[7]).</p>
     *
     * @param responseBody API'den donen JSON response
     * @return ham hisse verisi listesi
     */
    private List<RawStockData> parseSummaryResponse(String responseBody) {
        List<RawStockData> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode dataArray = root.get("data");
            if (dataArray == null || !dataArray.isArray()) {
                return result;
            }

            for (int i = 0; i < dataArray.size(); i++) {
                JsonNode item = dataArray.get(i);
                if (item == null) continue;

                // ticker: "s" field -> "BIST:GARAN" -> "GARAN"
                String ticker = "N/A";
                JsonNode sNode = item.get("s");
                if (sNode != null) {
                    ticker = sNode.asText().replace("BIST:", "");
                }

                JsonNode dArray = item.get("d");
                if (dArray == null || !dArray.isArray()) continue;

                // d[4] = change (changePercent)
                double changePercent = dArray.size() > 4 && dArray.get(4).isNumber()
                        ? dArray.get(4).asDouble() : 0.0;

                // d[6] = sector
                String tvSector = dArray.size() > 6 && !dArray.get(6).isNull()
                        ? dArray.get(6).asText() : null;

                // d[7] = industry
                String tvIndustry = dArray.size() > 7 && !dArray.get(7).isNull()
                        ? dArray.get(7).asText() : null;

                result.add(new RawStockData(ticker, changePercent, tvSector, tvIndustry));
            }
        } catch (Exception e) {
            log.error("[SECTOR-SERVICE] Summary response parse hatasi", e);
        }
        return result;
    }

    /**
     * Tum hisse taramasi sonuclarini gecici olarak tutan ic sinif.
     *
     * <p>Yalnizca sektor esleme ve ozet hesaplama icin kullanilir,
     * dis dunyaya expose edilmez.</p>
     *
     * @param ticker        hisse borsa kodu
     * @param changePercent gunluk degisim yuzdesi
     * @param tvSector      TradingView sector degeri
     * @param tvIndustry    TradingView industry degeri
     */
    private record RawStockData(String ticker, double changePercent, String tvSector, String tvIndustry) {
    }
}
