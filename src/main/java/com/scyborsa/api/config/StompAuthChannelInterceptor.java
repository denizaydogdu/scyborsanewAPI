package com.scyborsa.api.config;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;

import java.security.Principal;

/**
 * STOMP CONNECT frame'inden kullanici kimligini cozumleyen kanal interceptor'u.
 *
 * <p>Handshake asamasinda query parametresi yerine, STOMP CONNECT frame'inin
 * {@code email} header'ini okuyarak {@link Principal} atar. Bu yaklasim
 * URL'de email bilgisinin acikta kalmasini onler.</p>
 *
 * @see WebSocketConfig#configureClientInboundChannel
 */
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    /**
     * STOMP CONNECT frame'inden email header'ini okur ve principal atar.
     *
     * @param message gelen mesaj
     * @param channel mesaj kanali
     * @return islenmis mesaj
     */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String email = accessor.getFirstNativeHeader("email");
            if (email != null && !email.isBlank()) {
                accessor.setUser(new StompPrincipal(email));
            }
        }
        return message;
    }

    /**
     * Basit Principal implementasyonu — kullanici email adresini tasir.
     *
     * <p>STOMP {@code convertAndSendToUser()} metodunun kullanici eslestirmesi
     * icin {@link #getName()} kullanicinin email adresini doner.</p>
     */
    record StompPrincipal(String email) implements Principal {

        /**
         * Kullanici email adresini doner.
         *
         * @return kullanici email adresi
         */
        @Override
        public String getName() {
            return email;
        }
    }
}
