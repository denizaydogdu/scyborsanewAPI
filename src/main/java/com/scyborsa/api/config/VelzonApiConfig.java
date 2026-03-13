package com.scyborsa.api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Velzon dis API'si icin yapilandirma sinifi.
 *
 * <p>{@code velzon.api} prefix'i altindaki property'leri okur:</p>
 * <ul>
 *   <li>{@code velzon.api.base-url} - API temel URL'i</li>
 *   <li>{@code velzon.api.api-key} - Kimlik dogrulama anahtari</li>
 *   <li>{@code velzon.api.connect-timeout-seconds} - HTTP client baglanti zaman asimi (varsayilan: 10)</li>
 *   <li>{@code velzon.api.request-timeout-seconds} - HTTP client istek zaman asimi (varsayilan: 15)</li>
 * </ul>
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "velzon.api")
public class VelzonApiConfig {

    /** Velzon API temel URL'i (api.velzon.tr). */
    private String baseUrl;

    /** X-API-KEY header ile gonderilen kimlik dogrulama anahtari. */
    private String apiKey;

    /** HTTP client baglanti zaman asimi (saniye). */
    private int connectTimeoutSeconds = 10;

    /** HTTP client istek zaman asimi (saniye). */
    private int requestTimeoutSeconds = 15;
}
