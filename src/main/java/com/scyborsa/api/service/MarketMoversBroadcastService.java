package com.scyborsa.api.service;

import com.scyborsa.api.dto.market.MarketMoverDto;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Piyasa hareketlilerini (market movers) WebSocket üzerinden yayinlayan servis.
 *
 * <p>STOMP protokolü ile asagidaki topic'lere mesaj gönderir:</p>
 * <ul>
 *   <li>{@code /topic/market/rising} - En cok yükselen hisseler</li>
 *   <li>{@code /topic/market/falling} - En cok düsen hisseler</li>
 *   <li>{@code /topic/market/volume} - En yüksek hacimli hisseler</li>
 * </ul>
 *
 * <p>Bagimliliklar:</p>
 * <ul>
 *   <li>{@link org.springframework.messaging.simp.SimpMessagingTemplate} - STOMP mesaj gönderimi</li>
 * </ul>
 *
 * @see MarketMoversJob
 * @see MarketMoversCache
 */
@Service
@RequiredArgsConstructor
public class MarketMoversBroadcastService {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * En cok yükselen hisseleri {@code /topic/market/rising} topic'ine yayinlar.
     *
     * @param stocks yayinlanacak yükselen hisse listesi
     */
    public void broadcastRising(List<MarketMoverDto> stocks) {
        messagingTemplate.convertAndSend("/topic/market/rising", stocks);
    }

    /**
     * En cok düsen hisseleri {@code /topic/market/falling} topic'ine yayinlar.
     *
     * @param stocks yayinlanacak düsen hisse listesi
     */
    public void broadcastFalling(List<MarketMoverDto> stocks) {
        messagingTemplate.convertAndSend("/topic/market/falling", stocks);
    }

    /**
     * En yüksek hacimli hisseleri {@code /topic/market/volume} topic'ine yayinlar.
     *
     * @param stocks yayinlanacak yüksek hacimli hisse listesi
     */
    public void broadcastVolume(List<MarketMoverDto> stocks) {
        messagingTemplate.convertAndSend("/topic/market/volume", stocks);
    }
}
