package com.scyborsa.api.controller;

import com.scyborsa.api.dto.ChartSubscribeRequest;
import com.scyborsa.api.service.chart.TradingViewBarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Grafik verileri için WebSocket (STOMP) controller'ı.
 *
 * <p>İstemcilerin belirli sembol ve periyotlara WebSocket üzerinden abone olmasını yönetir.
 * Her oturum için abonelikleri takip eder ve bağlantı kesildiğinde otomatik temizlik yapar.</p>
 *
 * <p>STOMP mesaj yolu: {@code /app/chart/subscribe}</p>
 *
 * @see com.scyborsa.api.service.chart.TradingViewBarService
 * @see com.scyborsa.api.dto.ChartSubscribeRequest
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChartWebSocketController {

    private final TradingViewBarService barService;

    // Track all subscriptions per session: sessionId -> Set<"SYMBOL:PERIOD">
    private final ConcurrentHashMap<String, Set<String>> sessionSubscriptions = new ConcurrentHashMap<>();

    /**
     * Belirtilen sembol ve periyot için grafik verisine abone olur.
     *
     * <p>STOMP {@code /app/chart/subscribe}</p>
     *
     * <p>Abone sayısını artırır, gerekiyorsa TradingView'dan veri çekmeyi başlatır
     * ve oturum bazlı abonelik takibi yapar.</p>
     *
     * @param request        abone olunacak sembol, periyot ve bar sayısı bilgilerini içeren istek
     * @param headerAccessor WebSocket oturum bilgilerine erişim sağlayan başlık erişimcisi
     */
    @MessageMapping("/chart/subscribe")
    public void subscribe(ChartSubscribeRequest request, SimpMessageHeaderAccessor headerAccessor) {
        String sessionId = headerAccessor.getSessionId();
        String symbol = request.getSymbol();
        String period = request.getPeriod();
        String key = symbol + ":" + period;

        log.info("[WS-CTRL] Subscribe: session={} symbol={} period={}", sessionId, symbol, period);

        // Track for cleanup on disconnect — supports multiple subscriptions per session
        sessionSubscriptions.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(key);

        // Increment subscriber count + trigger TradingView subscription if needed
        barService.addSubscriber(symbol, period);

        // Ensure bars are being fetched
        barService.getBars(symbol, period, request.getBars());
    }

    /**
     * WebSocket oturumu kapandığında tetiklenen temizlik işlemi.
     *
     * <p>Oturuma ait tüm abonelikleri kaldırır ve ilgili sembollerin abone sayısını düşürür.
     * Abone kalmayan semboller için TradingView veri akışı otomatik olarak durdurulur.</p>
     *
     * @param event oturum kapanma olayı
     */
    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        Set<String> subscriptions = sessionSubscriptions.remove(sessionId);

        if (subscriptions != null) {
            for (String subscription : subscriptions) {
                String[] parts = subscription.split(":", 2);
                if (parts.length == 2) {
                    String symbol = parts[0];
                    String period = parts[1];
                    log.info("[WS-CTRL] Disconnect cleanup: session={} symbol={} period={}", sessionId, symbol, period);
                    barService.removeSubscriber(symbol, period);
                }
            }
            log.info("[WS-CTRL] Session disconnect: {} ({} subscription temizlendi)", sessionId, subscriptions.size());
        }
    }
}
