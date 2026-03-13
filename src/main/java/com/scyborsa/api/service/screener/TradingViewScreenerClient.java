package com.scyborsa.api.service.screener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.config.TradingViewConfig;
import com.scyborsa.api.dto.screener.ScanBodyDefinition;
import com.scyborsa.api.dto.screener.TvScreenerResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * TradingView Scanner API'sine doğrudan HTTP isteği gönderen client.
 *
 * <p>{@code scanner.tradingview.com/turkey/scan} endpoint'ine POST isteği yaparak
 * tarama sonuçlarını çeker. VelzonApiClient'tan farklı olarak TradingView'ın
 * kendi cookie tabanlı kimlik doğrulamasını kullanır.</p>
 *
 * @see TradingViewConfig
 * @see ScanBodyDefinition
 * @see TvScreenerResponse
 */
@Slf4j
@Service
public class TradingViewScreenerClient {

    private final TradingViewConfig tradingViewConfig;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String screenerUrl;

    /**
     * Constructor injection ile bağımlılıkları alır ve HTTP client oluşturur.
     *
     * @param tradingViewConfig API URL ve cookie konfigürasyonu
     * @param objectMapper JSON parse için Jackson ObjectMapper
     */
    public TradingViewScreenerClient(TradingViewConfig tradingViewConfig, ObjectMapper objectMapper) {
        this.tradingViewConfig = tradingViewConfig;
        this.objectMapper = objectMapper;
        this.screenerUrl = tradingViewConfig.getScreenerApiUrl();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(tradingViewConfig.getHttpConnectTimeoutSeconds()))
                .build();
    }

    /**
     * Tek bir scan body için TradingView Scanner API'sine istek gönderir.
     *
     * @param scanBody çalıştırılacak tarama tanımı
     * @return API response; hata durumunda {@code null}
     */
    public TvScreenerResponse executeScan(ScanBodyDefinition scanBody) {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(screenerUrl))
                    .timeout(Duration.ofSeconds(tradingViewConfig.getHttpRequestTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Origin", tradingViewConfig.getHeadersOrigin())
                    .header("Referer", tradingViewConfig.getHeadersReferer())
                    .header("User-Agent", TradingViewConfig.DEFAULT_USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofString(scanBody.body()));

            String cookie = tradingViewConfig.getScreenerCookie();
            if (cookie != null && !cookie.isBlank()) {
                requestBuilder.header("Cookie", cookie);
            }

            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
                log.warn("[SCREENER-CLIENT] API hatası: status={}, scan={}", response.statusCode(), scanBody.name());
                return null;
            }

            TvScreenerResponse result = objectMapper.readValue(response.body(), TvScreenerResponse.class);
            result.setScreenerName(scanBody.name());
            return result;
        } catch (Exception e) {
            log.error("[SCREENER-CLIENT] Scan başarısız: {}", scanBody.name(), e);
            return null;
        }
    }
}
