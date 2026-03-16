package com.scyborsa.api.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Velzon AI API yapilandirma sinifi.
 *
 * <p>{@code velzon.ai} prefix'i altindaki property'leri okur:</p>
 * <ul>
 *   <li>{@code velzon.ai.url} - API endpoint URL'i</li>
 *   <li>{@code velzon.ai.api-key} - API anahtari</li>
 *   <li>{@code velzon.ai.enabled} - Servis aktif mi</li>
 *   <li>{@code velzon.ai.timeout} - HTTP timeout (saniye)</li>
 *   <li>{@code velzon.ai.max-tokens} - Maksimum token sayisi</li>
 *   <li>{@code velzon.ai.temperature} - Model temperature</li>
 *   <li>{@code velzon.ai.rate-limit} - Istekler arasi minimum bekleme (ms)</li>
 * </ul>
 *
 * <p><b>Guvenlik:</b> {@code @Getter/@Setter} kullanilir ({@code @Data} degil)
 * cunku toString() apiKey'i log'lara sizdirir.</p>
 *
 * @see com.scyborsa.api.service.ai.VelzonAiService
 */
@Slf4j
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "velzon.ai")
public class VelzonAiConfig {

    /** Velzon AI API endpoint URL'i. */
    private String url;

    /** API erisim anahtari. */
    private String apiKey;

    /** AI servisi aktif mi? Default: false. */
    private boolean enabled = false;

    /** HTTP timeout suresi (saniye). */
    private int timeout = 60;

    /** Maksimum uretilecek token sayisi. */
    private int maxTokens = 500;

    /** Model temperature (0.0-1.0 arasi). */
    private double temperature = 0.7;

    /** Istekler arasi minimum bekleme suresi (milisaniye). */
    private int rateLimit = 1000;

    /**
     * Config validasyonu — enabled=true iken apiKey ve url zorunlu.
     *
     * <p>Tum eksik alanlari tek seferde loglar (sequential short-circuit yok).</p>
     */
    @PostConstruct
    void validate() {
        if (!enabled) return;

        boolean invalid = false;
        if (apiKey == null || apiKey.isBlank() || "CHANGE_ME".equals(apiKey)) {
            log.warn("[AI-CONFIG] velzon.ai.enabled=true ama apiKey tanimli degil");
            invalid = true;
        }
        if (url == null || url.isBlank()) {
            log.warn("[AI-CONFIG] velzon.ai.enabled=true ama url tanimli degil");
            invalid = true;
        }
        if (invalid) {
            log.warn("[AI-CONFIG] Eksik yapilandirma nedeniyle AI devre disi birakiliyor");
            this.enabled = false;
        }
    }
}
