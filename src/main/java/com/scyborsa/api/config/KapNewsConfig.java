package com.scyborsa.api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * KAP canlı haber API'si için yapılandırma sınıfı.
 *
 * <p>{@code kap.news} prefix'i altındaki property'leri okur:</p>
 * <ul>
 *   <li>{@code kap.news.api-url} - TradingView news-mediator API URL'i</li>
 *   <li>{@code kap.news.market-news-url} - TradingView headlines piyasa haberleri API URL'i</li>
 *   <li>{@code kap.news.world-news-url} - TradingView news-mediator dünya haberleri API URL'i</li>
 *   <li>{@code kap.news.logo-base-url} - Sembol logo CDN base URL'i</li>
 *   <li>{@code kap.news.story-base-url} - Haber detay sayfası base URL'i</li>
 *   <li>{@code kap.news.connect-timeout-seconds} - HTTP client baglanti zaman asimi (varsayilan: 10)</li>
 *   <li>{@code kap.news.request-timeout-seconds} - HTTP client istek zaman asimi (varsayilan: 15)</li>
 * </ul>
 *
 * @see com.scyborsa.api.service.kap.KapNewsClient
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "kap.news")
public class KapNewsConfig {

    /** TradingView news-mediator API URL'i. */
    private String apiUrl;

    /** Piyasa haberleri (headlines) API URL'i. */
    private String marketNewsUrl;

    /** Dünya haberleri (news-mediator) API URL'i. */
    private String worldNewsUrl;

    /** Sembol logo CDN base URL'i. */
    private String logoBaseUrl;

    /** Haber detay sayfası base URL'i (storyPath prefix). */
    private String storyBaseUrl;

    /** HTTP client baglanti zaman asimi (saniye). */
    private int connectTimeoutSeconds = 10;

    /** HTTP client istek zaman asimi (saniye). */
    private int requestTimeoutSeconds = 15;
}
