package com.scyborsa.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.config.TradingViewConfig;
import com.scyborsa.api.dto.market.MarketMoverDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * TradingView Screener API'den BIST piyasa hareketlileri verilerini ceken servis.
 *
 * <p>Dogrudan TradingView'in screener endpoint'ine HTTP POST istegi göndererek
 * en cok yükselen, en cok düsen ve en yüksek hacimli hisseleri cekerler.</p>
 *
 * <p>Her sorgu ilk 10 sonucu cekerr, response'tan ilk 5'i parse edilerek
 * {@link MarketMoverDto} listesine dönüstürülür.</p>
 *
 * <p>Bagimliliklar:</p>
 * <ul>
 *   <li>{@link com.scyborsa.api.config.TradingViewConfig} - API URL ve cookie konfigürasyonu</li>
 *   <li>{@link com.fasterxml.jackson.databind.ObjectMapper} - JSON parse islemi</li>
 * </ul>
 *
 * @see com.scyborsa.api.service.job.MarketMoversJob
 */
@Slf4j
@Service
public class ScreenerService {

    /** En cok yukselen BIST hisseleri icin TradingView scan body JSON'u. */
    private static final String RISING_BODY = "{\"columns\":[\"name\",\"description\",\"logoid\",\"update_mode\",\"type\",\"typespecs\",\"close\",\"pricescale\",\"minmov\",\"fractional\",\"minmove2\",\"currency\",\"change\"],\"ignore_unknown_fields\":false,\"options\":{\"lang\":\"tr\"},\"range\":[0,10],\"sort\":{\"sortBy\":\"change\",\"sortOrder\":\"desc\"},\"symbols\":{},\"markets\":[\"turkey\"],\"filter2\":{\"operator\":\"and\",\"operands\":[{\"operation\":{\"operator\":\"or\",\"operands\":[{\"operation\":{\"operator\":\"and\",\"operands\":[{\"expression\":{\"left\":\"type\",\"operation\":\"equal\",\"right\":\"stock\"}},{\"expression\":{\"left\":\"typespecs\",\"operation\":\"has\",\"right\":[\"common\"]}}]}},{\"operation\":{\"operator\":\"and\",\"operands\":[{\"expression\":{\"left\":\"type\",\"operation\":\"equal\",\"right\":\"stock\"}},{\"expression\":{\"left\":\"typespecs\",\"operation\":\"has\",\"right\":[\"preferred\"]}}]}},{\"operation\":{\"operator\":\"and\",\"operands\":[{\"expression\":{\"left\":\"type\",\"operation\":\"equal\",\"right\":\"dr\"}}]}},{\"operation\":{\"operator\":\"and\",\"operands\":[{\"expression\":{\"left\":\"type\",\"operation\":\"equal\",\"right\":\"fund\"}},{\"expression\":{\"left\":\"typespecs\",\"operation\":\"has_none_of\",\"right\":[\"etf\"]}}]}}]}}]}}";
    /** En cok dusen BIST hisseleri icin TradingView scan body JSON'u. */
    private static final String FALLING_BODY = "{\"columns\":[\"name\",\"description\",\"logoid\",\"update_mode\",\"type\",\"typespecs\",\"close\",\"pricescale\",\"minmov\",\"fractional\",\"minmove2\",\"currency\",\"change\",\"volume\"],\"ignore_unknown_fields\":false,\"options\":{\"lang\":\"tr\"},\"range\":[0,10],\"sort\":{\"sortBy\":\"change\",\"sortOrder\":\"asc\"},\"symbols\":{},\"markets\":[\"turkey\"],\"filter2\":{\"operator\":\"and\",\"operands\":[{\"operation\":{\"operator\":\"or\",\"operands\":[{\"operation\":{\"operator\":\"and\",\"operands\":[{\"expression\":{\"left\":\"type\",\"operation\":\"equal\",\"right\":\"stock\"}},{\"expression\":{\"left\":\"typespecs\",\"operation\":\"has\",\"right\":[\"common\"]}}]}},{\"operation\":{\"operator\":\"and\",\"operands\":[{\"expression\":{\"left\":\"type\",\"operation\":\"equal\",\"right\":\"stock\"}},{\"expression\":{\"left\":\"typespecs\",\"operation\":\"has\",\"right\":[\"preferred\"]}}]}},{\"operation\":{\"operator\":\"and\",\"operands\":[{\"expression\":{\"left\":\"type\",\"operation\":\"equal\",\"right\":\"dr\"}}]}},{\"operation\":{\"operator\":\"and\",\"operands\":[{\"expression\":{\"left\":\"type\",\"operation\":\"equal\",\"right\":\"fund\"}},{\"expression\":{\"left\":\"typespecs\",\"operation\":\"has_none_of\",\"right\":[\"etf\"]}}]}}]}}]}}";
    /** En yuksek hacimli BIST hisseleri icin TradingView scan body JSON'u. */
    private static final String VOLUME_BODY = "{\"columns\":[\"name\",\"description\",\"logoid\",\"update_mode\",\"type\",\"typespecs\",\"close\",\"pricescale\",\"minmov\",\"fractional\",\"minmove2\",\"currency\",\"change\",\"volume\"],\"ignore_unknown_fields\":false,\"options\":{\"lang\":\"tr\"},\"range\":[0,10],\"sort\":{\"sortBy\":\"volume\",\"sortOrder\":\"desc\"},\"symbols\":{},\"markets\":[\"turkey\"],\"filter2\":{\"operator\":\"and\",\"operands\":[{\"operation\":{\"operator\":\"or\",\"operands\":[{\"operation\":{\"operator\":\"and\",\"operands\":[{\"expression\":{\"left\":\"type\",\"operation\":\"equal\",\"right\":\"stock\"}},{\"expression\":{\"left\":\"typespecs\",\"operation\":\"has\",\"right\":[\"common\"]}}]}},{\"operation\":{\"operator\":\"and\",\"operands\":[{\"expression\":{\"left\":\"type\",\"operation\":\"equal\",\"right\":\"stock\"}},{\"expression\":{\"left\":\"typespecs\",\"operation\":\"has\",\"right\":[\"preferred\"]}}]}},{\"operation\":{\"operator\":\"and\",\"operands\":[{\"expression\":{\"left\":\"type\",\"operation\":\"equal\",\"right\":\"dr\"}}]}},{\"operation\":{\"operator\":\"and\",\"operands\":[{\"expression\":{\"left\":\"type\",\"operation\":\"equal\",\"right\":\"fund\"}},{\"expression\":{\"left\":\"typespecs\",\"operation\":\"has_none_of\",\"right\":[\"etf\"]}}]}}]}}]}}";

    /** TradingView API konfigurasyonu (URL, cookie, header). */
    private final TradingViewConfig tradingViewConfig;

    /** HTTP istekleri icin Java 11 HttpClient. */
    private final HttpClient httpClient;

    /** JSON parse icin Jackson ObjectMapper. */
    private final ObjectMapper objectMapper;

    /** TradingView Scanner API URL'i. */
    private final String screenerUrl;

    /**
     * Constructor injection ile bagimliliklari alir ve HTTP client'i olusturur.
     *
     * @param tradingViewConfig TradingView API konfigürasyonu (URL, cookie)
     * @param objectMapper      JSON parse icin Jackson ObjectMapper
     */
    public ScreenerService(TradingViewConfig tradingViewConfig, ObjectMapper objectMapper) {
        this.tradingViewConfig = tradingViewConfig;
        this.objectMapper = objectMapper;
        this.screenerUrl = tradingViewConfig.getScreenerApiUrl();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(tradingViewConfig.getHttpConnectTimeoutSeconds()))
                .build();
    }

    /**
     * En cok yükselen BIST hisselerini cekerr.
     *
     * @return degisim yüzdesine göre azalan sirada en cok yükselen 5 hisse; hata durumunda bos liste
     */
    public List<MarketMoverDto> scanRising() {
        return scan(RISING_BODY);
    }

    /**
     * En cok düsen BIST hisselerini cekerr.
     *
     * @return degisim yüzdesine göre artan sirada en cok düsen 5 hisse; hata durumunda bos liste
     */
    public List<MarketMoverDto> scanFalling() {
        return scan(FALLING_BODY);
    }

    /**
     * En yüksek hacimli BIST hisselerini cekerr.
     *
     * @return islem hacmine göre azalan sirada en yüksek hacimli 5 hisse; hata durumunda bos liste
     */
    public List<MarketMoverDto> scanVolume() {
        return scan(VOLUME_BODY);
    }

    private List<MarketMoverDto> scan(String requestBody) {
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
                log.error("TradingView Screener API hatası: status={}", response.statusCode());
                return List.of();
            }

            return parseResponse(response.body());
        } catch (Exception e) {
            log.error("TradingView Screener API çağrısı başarısız", e);
            return List.of();
        }
    }

    private List<MarketMoverDto> parseResponse(String responseBody) {
        List<MarketMoverDto> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode dataArray = root.get("data");
            if (dataArray == null || !dataArray.isArray()) {
                return result;
            }

            int limit = Math.min(5, dataArray.size());
            for (int i = 0; i < limit; i++) {
                JsonNode item = dataArray.get(i);
                if (item == null) continue;

                // ticker from "s" field: "BIST:THYAO" → "THYAO"
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

                // d[2] = logoid
                String logoid = dArray.size() > 2 && !dArray.get(2).isNull()
                        ? dArray.get(2).asText() : null;

                // d[6] = close price
                double price = dArray.size() > 6 && dArray.get(6).isNumber()
                        ? dArray.get(6).asDouble() : 0.0;

                // d[12] = change percent
                double changePercent = dArray.size() > 12 && dArray.get(12).isNumber()
                        ? dArray.get(12).asDouble() : 0.0;

                result.add(new MarketMoverDto(ticker, description, price, changePercent, logoid));
            }
        } catch (Exception e) {
            log.error("Screener response parse hatası", e);
        }
        return result;
    }
}
