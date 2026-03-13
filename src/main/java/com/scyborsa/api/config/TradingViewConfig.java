package com.scyborsa.api.config;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * TradingView API ve WebSocket baglantilari icin yapilandirma sinifi.
 *
 * <p>Iki farkli kanal icin ayri kimlik bilgileri tasir:</p>
 * <ul>
 *   <li><b>WebSocket kanali:</b> Canli fiyat verisi icin ({@code tradingview.websocket.*})</li>
 *   <li><b>Screener kanali:</b> Tarama API'si icin ({@code tradingview.auth.*}, {@code tradingview.cookie.*})</li>
 * </ul>
 *
 * <p>WebSocket token bulunamazsa screener token'i fallback olarak kullanilir.</p>
 *
 * @see com.scyborsa.api.constants.TradingViewConstant
 */
@Slf4j
@Getter
@Setter
@Configuration
public class TradingViewConfig {

    /** TradingView HTTP isteklerinde kullanilan varsayilan User-Agent header degeri. */
    public static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** WebSocket kanali icin TradingView auth token. */
    @Value("${tradingview.websocket.auth.token:}")
    private String websocketAuthToken;

    /** WebSocket kanali icin TradingView cookie degeri. */
    @Value("${tradingview.websocket.cookie.value:}")
    private String websocketCookieValue;

    /** Screener kanali icin TradingView auth token. */
    @Value("${tradingview.auth.token:}")
    private String authToken;

    /** Screener kanali icin TradingView cookie degeri. */
    @Value("${tradingview.cookie.value:}")
    private String cookieValue;

    /** TradingView Scanner API URL'i (dogrudan baglanti — ADR-010). */
    @Value("${tradingview.screener.url:https://scanner.tradingview.com/turkey/scan?label-product=screener-stock}")
    private String screenerApiUrl;

    /** TradingView HTTP isteklerinde kullanilan Origin header degeri. */
    @Value("${tradingview.headers.origin:https://tr.tradingview.com}")
    private String headersOrigin;

    /** TradingView HTTP isteklerinde kullanilan Referer header degeri. */
    @Value("${tradingview.headers.referer:https://tr.tradingview.com/}")
    private String headersReferer;

    /** HTTP client baglanti zaman asimi (saniye). */
    @Value("${tradingview.http.connect-timeout-seconds:10}")
    private int httpConnectTimeoutSeconds;

    /** HTTP client istek zaman asimi (saniye). */
    @Value("${tradingview.http.request-timeout-seconds:15}")
    private int httpRequestTimeoutSeconds;

    /** WebSocket baglanti bekleme suresi (milisaniye). */
    @Value("${tradingview.chart.websocket-wait-ms:200}")
    private int chartWebsocketWaitMs;

    /**
     * Uygulama basladiginda konfigurasyonun durumunu loglar.
     *
     * <p>WebSocket token ve cookie degerlerinin yapilandirilip yapilandirilmadigini raporlar.</p>
     */
    @PostConstruct
    public void init() {
        log.info("TradingViewConfig yüklendi:");
        log.info("  [WEBSOCKET] Token: {}, Cookie: {}",
                isWebsocketAuthTokenConfigured() ? "configured" : "NOT configured",
                isWebsocketCookieConfigured() ? "configured" : "NOT configured");
    }

    /**
     * WebSocket auth token'inin yapilandirilip yapilandirilmadigini kontrol eder.
     *
     * @return token dolu ve bos degilse {@code true}
     */
    public boolean isWebsocketAuthTokenConfigured() {
        return websocketAuthToken != null && !websocketAuthToken.isBlank();
    }

    /**
     * WebSocket cookie degerinin yapilandirilip yapilandirilmadigini kontrol eder.
     *
     * @return cookie dolu ve bos degilse {@code true}
     */
    public boolean isWebsocketCookieConfigured() {
        return websocketCookieValue != null && !websocketCookieValue.isBlank();
    }

    /**
     * WebSocket baglantisi icin auth token'ini dondurur.
     *
     * <p>Eger WebSocket token yapilandirilmamissa, screener auth token'i fallback olarak kullanilir.</p>
     *
     * @return WebSocket auth token'i veya fallback olarak screener token'i
     */
    public String getWebsocketAuthToken() {
        if (isWebsocketAuthTokenConfigured()) {
            return websocketAuthToken;
        }
        log.warn("WebSocket auth token bulunamadı, screener token kullanılıyor!");
        return authToken;
    }

    /**
     * WebSocket baglantisi icin ham cookie degerini dondurur.
     *
     * @return WebSocket cookie degeri, yapilandirilmamissa bos string
     */
    public String getWebsocketCookieValue() {
        return websocketCookieValue;
    }

    /**
     * WebSocket baglantisi icin gerekli cookie'leri dondurur.
     *
     * @return cookie degeri; yapilandirilmamissa bos string
     */
    public String getWebsocketEssentialCookies() {
        if (websocketCookieValue == null || websocketCookieValue.isBlank()) {
            return "";
        }
        return websocketCookieValue;
    }

    /**
     * Screener API istekleri icin kullanilacak cookie degerini dondurur.
     *
     * <p>Oncelikli olarak screener cookie'sini kullanir; yapilandirilmamissa
     * WebSocket cookie'sine duser.</p>
     *
     * @return screener cookie degeri veya fallback olarak WebSocket cookie degeri
     */
    public String getScreenerCookie() {
        if (cookieValue != null && !cookieValue.isBlank()) return cookieValue;
        return websocketCookieValue;
    }
}
