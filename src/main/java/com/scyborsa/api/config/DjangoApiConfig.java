package com.scyborsa.api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Django API (velzon-django) baglanti konfigurasyonu.
 *
 * <p>Formasyon tarama verilerini saglayan Django uygulamasina
 * HTTP baglantisi icin gerekli ayarlari icerir.</p>
 *
 * <p>{@code django.api} prefix'i altindaki property'leri okur:</p>
 * <ul>
 *   <li>{@code django.api.base-url} - Django API temel URL'i</li>
 *   <li>{@code django.api.api-key} - Kimlik dogrulama anahtari (X-API-KEY)</li>
 *   <li>{@code django.api.connect-timeout-seconds} - HTTP client baglanti zaman asimi (varsayilan: 10)</li>
 *   <li>{@code django.api.request-timeout-seconds} - HTTP client istek zaman asimi (varsayilan: 30)</li>
 * </ul>
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "django.api")
public class DjangoApiConfig {

    /** Django API base URL (orn. http://209.38.247.159:8001). */
    private String baseUrl = "";

    /** Django API key (X-API-KEY header). */
    private String apiKey = "";

    /** Baglanti timeout (saniye). */
    private int connectTimeoutSeconds = 10;

    /** Istek timeout (saniye). */
    private int requestTimeoutSeconds = 30;
}
