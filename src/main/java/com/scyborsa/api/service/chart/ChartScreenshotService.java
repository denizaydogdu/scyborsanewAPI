package com.scyborsa.api.service.chart;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.config.TelegramConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * CHART-IMG API uzerinden TradingView chart screenshot servisi.
 *
 * <p>Belirtilen hisse kodu icin TradingView layout screenshot'i alir.
 * Telegram mesajlarina gorsel ek olarak gonderilmek uzere PNG binary veri dondurur.</p>
 *
 * <p><b>Graceful degradation (ADR-017):</b> Tum hata durumlari yakalanir,
 * exception firlatilmaz, {@code null} dondurulur. Telegram mesaji text olarak
 * fallback yapilir.</p>
 *
 * <p>Konfiguerasyon {@link TelegramConfig.Screenshot} uzerinden saglanir
 * (API key, layout ID, boyut, tema vb.).</p>
 *
 * @see TelegramConfig.Screenshot
 * @see com.scyborsa.api.service.telegram.TelegramSendService
 */
@Slf4j
@Service
public class ChartScreenshotService {

    /** Maksimum kabul edilebilir gorsel boyutu (10 MB). */
    private static final int MAX_IMAGE_SIZE = 10 * 1024 * 1024;

    /** PNG dosya imzasi (magic bytes). */
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47};

    /** BIST sembol prefix'i. */
    private static final String BIST_PREFIX = "BIST:";

    /** Telegram bot yapilandirma bilgileri. */
    private final TelegramConfig telegramConfig;

    /** JSON serialization. */
    private final ObjectMapper objectMapper;

    /** Java 11 HTTP istemcisi. */
    private final HttpClient httpClient;

    /**
     * Constructor injection ile bagimliliklari alir ve HTTP client olusturur.
     *
     * @param telegramConfig Telegram yapilandirmasi (screenshot ayarlari dahil)
     * @param objectMapper   JSON serialization
     */
    public ChartScreenshotService(TelegramConfig telegramConfig, ObjectMapper objectMapper) {
        this.telegramConfig = telegramConfig;
        this.objectMapper = objectMapper;

        TelegramConfig.Screenshot cfg = telegramConfig.getScreenshot();
        int timeoutSec = cfg.getTimeout() > 0 ? cfg.getTimeout() : 30;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSec))
                .build();
    }

    /**
     * Belirtilen hisse icin chart screenshot yakalar.
     *
     * <p>Graceful degradation: hata durumunda {@code null} doner, exception firlatmaz.
     * Arayan taraf {@code null} kontrolu ile text fallback yapmalidir.</p>
     *
     * <p>Islem akisi:</p>
     * <ol>
     *   <li>Sembol olusturma: "BIST:" + stockCode</li>
     *   <li>JSON request body hazirlama (symbol, interval, width, height, format)</li>
     *   <li>POST /v2/tradingview/layout-chart/{layoutId}</li>
     *   <li>Response dogrulama: HTTP 200, PNG magic bytes, boyut &lt; 10MB</li>
     * </ol>
     *
     * @param stockCode hisse kodu (orn: GARAN)
     * @return PNG binary veri veya {@code null} (hata/devre disi durumunda)
     */
    public byte[] captureChartScreenshot(String stockCode) {
        if (!isEnabled()) {
            return null;
        }

        if (stockCode == null || stockCode.isBlank()) {
            log.warn("[CHART-SCREENSHOT] Hisse kodu bos, screenshot atlaniyor");
            return null;
        }

        TelegramConfig.Screenshot cfg = telegramConfig.getScreenshot();
        String symbol = BIST_PREFIX + stockCode.trim().toUpperCase(Locale.ROOT);

        try {
            // 1. JSON request body
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("symbol", symbol);
            body.put("interval", cfg.getInterval());
            body.put("width", cfg.getWidth());
            body.put("height", cfg.getHeight());
            body.put("format", cfg.getFormat());
            body.put("theme", cfg.getTheme());
            body.put("timezone", cfg.getTimezone());

            String jsonBody = objectMapper.writeValueAsString(body);

            // 2. API URL
            String url = cfg.getApiUrl() + "/v2/tradingview/layout-chart/" + cfg.getLayoutId();

            // 3. HTTP POST
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(cfg.getTimeout()))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", cfg.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            log.debug("[CHART-SCREENSHOT] API cagrisi: {} | symbol={}", url, symbol);

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            // 4. Response dogrulama
            return validateAndExtract(response, stockCode);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[CHART-SCREENSHOT] API cagrisi interrupted: {}", stockCode);
            return null;
        } catch (Exception e) {
            log.warn("[CHART-SCREENSHOT] API cagrisi basarisiz ({}): {}", stockCode, e.getMessage());
            return null;
        }
    }

    /**
     * Screenshot servisinin aktif ve kullanimda olup olmadigini kontrol eder.
     *
     * <p>Aktif olmasi icin:</p>
     * <ul>
     *   <li>{@code telegram.screenshot.enabled = true}</li>
     *   <li>{@code telegram.screenshot.api-key} dolu olmali</li>
     * </ul>
     *
     * @return servis kullanilabilir ise {@code true}
     */
    public boolean isEnabled() {
        TelegramConfig.Screenshot cfg = telegramConfig.getScreenshot();
        return cfg.isEnabled()
                && cfg.getApiKey() != null
                && !cfg.getApiKey().isBlank()
                && cfg.getLayoutId() != null
                && !cfg.getLayoutId().isBlank();
    }

    // ==================== PRIVATE ====================

    /**
     * HTTP response'u dogrular ve PNG binary veriyi cikarir.
     *
     * @param response HTTP response
     * @param stockCode log icin hisse kodu
     * @return PNG binary veri veya {@code null}
     */
    private byte[] validateAndExtract(HttpResponse<byte[]> response, String stockCode) {
        int status = response.statusCode();

        if (status != 200) {
            logHttpError(status, stockCode);
            return null;
        }

        // Content-Length on-kontrolu — buyuk response'larin heap'e yuklenmesinden ONCE
        response.headers().firstValueAsLong("Content-Length").ifPresent(len -> {
            if (len > MAX_IMAGE_SIZE) {
                log.warn("[CHART-SCREENSHOT] Content-Length cok buyuk: {} | {} bytes (max: {} bytes)",
                        stockCode, len, MAX_IMAGE_SIZE);
            }
        });

        byte[] imageBytes = response.body();

        // Bos response kontrolu
        if (imageBytes == null || imageBytes.length == 0) {
            log.warn("[CHART-SCREENSHOT] Bos response: {}", stockCode);
            return null;
        }

        // Boyut kontrolu
        if (imageBytes.length > MAX_IMAGE_SIZE) {
            log.warn("[CHART-SCREENSHOT] Gorsel cok buyuk: {} | {} bytes (max: {} bytes)",
                    stockCode, imageBytes.length, MAX_IMAGE_SIZE);
            return null;
        }

        // PNG magic bytes kontrolu
        if (!isPngImage(imageBytes)) {
            log.warn("[CHART-SCREENSHOT] Gecersiz PNG formati: {} | Ilk 4 byte: [{}, {}, {}, {}]",
                    stockCode,
                    imageBytes.length > 0 ? String.format("0x%02X", imageBytes[0]) : "?",
                    imageBytes.length > 1 ? String.format("0x%02X", imageBytes[1]) : "?",
                    imageBytes.length > 2 ? String.format("0x%02X", imageBytes[2]) : "?",
                    imageBytes.length > 3 ? String.format("0x%02X", imageBytes[3]) : "?");
            return null;
        }

        log.info("[CHART-SCREENSHOT] Screenshot alindi: {} | {} KB", stockCode, imageBytes.length / 1024);
        return imageBytes;
    }

    /**
     * HTTP hata koduna gore uygun seviyede log yazar.
     *
     * @param status HTTP status kodu
     * @param stockCode hisse kodu
     */
    private void logHttpError(int status, String stockCode) {
        switch (status) {
            case 400 -> log.warn("[CHART-SCREENSHOT] Bad request (400): {}", stockCode);
            case 403 -> log.error("[CHART-SCREENSHOT] Gecersiz API key (403): {}", stockCode);
            case 404 -> log.error("[CHART-SCREENSHOT] Layout bulunamadi (404): {}", stockCode);
            case 429 -> log.warn("[CHART-SCREENSHOT] Rate limit asildi (429): {}", stockCode);
            default -> {
                if (status >= 500) {
                    log.warn("[CHART-SCREENSHOT] Sunucu hatasi ({}): {}", status, stockCode);
                } else {
                    log.warn("[CHART-SCREENSHOT] Beklenmeyen HTTP status ({}): {}", status, stockCode);
                }
            }
        }
    }

    /**
     * Byte dizisinin PNG dosya imzasiyla basladigini kontrol eder.
     *
     * @param data binary veri
     * @return PNG ise {@code true}
     */
    private boolean isPngImage(byte[] data) {
        if (data.length < PNG_MAGIC.length) return false;
        return Arrays.equals(PNG_MAGIC, 0, PNG_MAGIC.length, data, 0, PNG_MAGIC.length);
    }
}
