package com.scyborsa.api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Fintables dis API'si icin yapilandirma sinifi.
 *
 * <p>{@code fintables.api} prefix'i altindaki property'leri okur:</p>
 * <ul>
 *   <li>{@code fintables.api.base-url} - API temel URL'i</li>
 *   <li>{@code fintables.api.bearer-token} - JWT Bearer token</li>
 *   <li>{@code fintables.api.connect-timeout-seconds} - HTTP client baglanti zaman asimi (varsayilan: 10)</li>
 *   <li>{@code fintables.api.request-timeout-seconds} - HTTP client istek zaman asimi (varsayilan: 15)</li>
 * </ul>
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "fintables.api")
public class FintablesApiConfig {

    private String baseUrl;
    private String bearerToken;

    /** Fintables API cookie (auth-token + tracking). */
    private String cookie;

    /** HTTP client baglanti zaman asimi (saniye). */
    private int connectTimeoutSeconds = 10;

    /** HTTP client istek zaman asimi (saniye). */
    private int requestTimeoutSeconds = 15;
}
