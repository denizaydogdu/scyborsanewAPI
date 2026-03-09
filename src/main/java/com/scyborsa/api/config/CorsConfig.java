package com.scyborsa.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * REST API endpoint'leri icin CORS (Cross-Origin Resource Sharing) yapilandirmasi.
 *
 * <p>Yalnizca {@code /api/v1/**} yolundaki REST endpoint'lerini kapsar.
 * WebSocket ({@code /ws}) icin CORS ayarlari {@link WebSocketConfig} sinifinda tanimlidir.</p>
 *
 * <p>Izin verilen origin ve cache suresi {@code application.yml} dosyasindan okunur:</p>
 * <ul>
 *   <li>{@code cors.allowed-origins} - Izin verilen origin (varsayilan: scyborsaUI)</li>
 *   <li>{@code cors.max-age-seconds} - Preflight cache suresi (varsayilan: 3600 saniye)</li>
 * </ul>
 *
 * @see WebSocketConfig
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /** CORS icin izin verilen origin adresi ({@code cors.allowed-origins}). */
    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    /** Preflight yanit cache suresi, saniye cinsinden ({@code cors.max-age-seconds}). */
    @Value("${cors.max-age-seconds:3600}")
    private long maxAgeSeconds;

    /**
     * REST endpoint'lerine CORS mapping tanimlarini ekler.
     *
     * <p>Yapilandirma detaylari:</p>
     * <ul>
     *   <li>Yol deseni: {@code /api/v1/**}</li>
     *   <li>Izin verilen origin: {@code http://localhost:8080}</li>
     *   <li>Izin verilen HTTP metotlari: GET, POST, PUT, DELETE, PATCH, OPTIONS</li>
     *   <li>Credential destegi: aktif</li>
     *   <li>Preflight cache suresi: 3600 saniye (1 saat)</li>
     * </ul>
     *
     * @param registry CORS kayit nesnesi
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // REST endpoints only. WebSocket (/ws) CORS is in WebSocketConfig.
        registry.addMapping("/api/v1/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowCredentials(true)
                .allowedHeaders("*")
                .maxAge(maxAgeSeconds);
    }
}
