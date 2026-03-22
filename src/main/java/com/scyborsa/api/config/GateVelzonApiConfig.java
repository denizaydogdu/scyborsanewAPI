package com.scyborsa.api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Gate Velzon API (gate.velzon.tr) baglanti konfigurasyonu.
 *
 * <p>Bilanco, rasyo ve finansal rapor verilerini saglayan Gate Velzon API'sine
 * HTTP baglantisi icin gerekli ayarlari icerir.</p>
 *
 * <p>{@code gate.velzon} prefix'i altindaki property'leri okur:</p>
 * <ul>
 *   <li>{@code gate.velzon.base-url} - Gate Velzon API temel URL'i</li>
 *   <li>{@code gate.velzon.api-key} - Kimlik dogrulama anahtari (X-API-KEY)</li>
 *   <li>{@code gate.velzon.connect-timeout-seconds} - HTTP client baglanti zaman asimi (varsayilan: 10)</li>
 *   <li>{@code gate.velzon.request-timeout-seconds} - HTTP client istek zaman asimi (varsayilan: 30)</li>
 * </ul>
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "gate.velzon")
public class GateVelzonApiConfig {

    /** Gate Velzon API base URL (orn. https://gate.velzon.tr). */
    private String baseUrl = "";

    /** Gate Velzon API key (X-API-KEY header). */
    private String apiKey = "";

    /** Baglanti timeout (saniye). */
    private int connectTimeoutSeconds = 10;

    /** Istek timeout (saniye). */
    private int requestTimeoutSeconds = 30;
}
