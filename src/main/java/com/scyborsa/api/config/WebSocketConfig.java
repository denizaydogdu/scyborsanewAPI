package com.scyborsa.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP tabanli WebSocket mesajlasma yapilandirmasi.
 *
 * <p>scyborsaUI ile canli veri iletisimi icin kullanilir.
 * Endpoint: {@code /ws} (SockJS destekli)</p>
 *
 * <p>Broker prefix'leri:</p>
 * <ul>
 *   <li>{@code /topic} - Broadcast mesajlar (ornegin canli fiyat akisi)</li>
 *   <li>{@code /queue} - Kullaniciya ozel mesajlar</li>
 * </ul>
 *
 * <p>Uygulama hedef prefix'i: {@code /app}</p>
 *
 * @see CorsConfig
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** CORS icin izin verilen origin adresi ({@code cors.allowed-origins}). */
    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    /**
     * Mesaj broker'ini yapilandirir.
     *
     * <p>{@code /topic} ve {@code /queue} prefix'lerinde basit bellek-ici broker aktiflestirilir.
     * Uygulama hedef prefix'i {@code /app} olarak ayarlanir.</p>
     *
     * @param config mesaj broker kayit nesnesi
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    /**
     * STOMP endpoint'lerini kaydeder.
     *
     * <p>{@code /ws} endpoint'i SockJS fallback destegi ile olusturulur.
     * Yalnizca {@code http://localhost:8080} origin'ine izin verilir.</p>
     *
     * @param registry STOMP endpoint kayit nesnesi
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins(allowedOrigins)
                .withSockJS();
    }

    /**
     * Client inbound kanal interceptor'unu kaydeder.
     *
     * <p>STOMP CONNECT frame'inden email header'ini okuyarak
     * kullanici principal'ini atar.</p>
     *
     * @param registration kanal kayit nesnesi
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new StompAuthChannelInterceptor());
    }
}
