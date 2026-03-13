package com.scyborsa.api.service.chart;

import com.scyborsa.api.model.chart.BarUpdateMessage;
import com.scyborsa.api.model.chart.CandleBar;
import com.scyborsa.api.model.chart.SymbolSubscription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mum grafigi (candlestick) bar verilerini ve fiyat kotasyonlarini WebSocket üzerinden yayinlayan servis.
 *
 * <p>STOMP protokolü ile asagidaki topic'lere mesaj gönderir:</p>
 * <ul>
 *   <li>{@code /topic/bars/{symbol}/{period}} - Bar (OHLCV) güncellemeleri</li>
 *   <li>{@code /topic/price/{symbol}} - Anlik fiyat kotasyonlari</li>
 * </ul>
 *
 * <p>Bagimliliklar:</p>
 * <ul>
 *   <li>{@link org.springframework.messaging.simp.SimpMessagingTemplate} - STOMP mesaj gönderimi</li>
 *   <li>{@link QuotePriceCache} - Fiyat kotasyonlari cache'i</li>
 * </ul>
 *
 * @see TradingViewBarService
 * @see BarCache
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BarBroadcastService {

    /** STOMP mesaj gonderimi icin Spring messaging template. */
    private final SimpMessagingTemplate messagingTemplate;
    /** Anlik fiyat kotasyonlarinin tutuldugu cache. */
    private final QuotePriceCache quotePriceCache;

    /**
     * Bir subscription icin ilk bar verilerini (initial load) WebSocket'e yayinlar.
     *
     * <p>Mesaj tipi {@code "initial"} olarak isaretlenir. Tüm bar'lar tek seferde gönderilir.</p>
     *
     * @param sub yayinlanacak subscription (symbol, period ve bar listesi icerir)
     */
    public void broadcastInitialLoad(SymbolSubscription sub) {
        String topic = buildTopic(sub.getSymbol(), sub.getPeriod());
        BarUpdateMessage msg = BarUpdateMessage.builder()
                .symbol(cleanSymbol(sub.getSymbol()))
                .period(sub.getPeriod())
                .type("initial")
                .bars(new ArrayList<>(sub.getBars()))
                .totalBars(sub.getBars().size())
                .serverTime(System.currentTimeMillis())
                .build();

        messagingTemplate.convertAndSend(topic, msg);
        log.info("[BROADCAST] Initial load: {} ({} bars) -> {}", sub.getCacheKey(), sub.getBars().size(), topic);
    }

    /**
     * Bir subscription icin güncel bar verilerini (update/new) WebSocket'e yayinlar.
     *
     * @param sub         güncellenen subscription
     * @param updatedBars güncellenen veya yeni olusan bar'lar
     * @param updateType  güncelleme tipi (ör. "update", "new")
     */
    public void broadcastBarUpdate(SymbolSubscription sub, List<CandleBar> updatedBars, String updateType) {
        String topic = buildTopic(sub.getSymbol(), sub.getPeriod());

        BarUpdateMessage msg = BarUpdateMessage.builder()
                .symbol(cleanSymbol(sub.getSymbol()))
                .period(sub.getPeriod())
                .type(updateType)
                .bars(updatedBars)
                .totalBars(sub.getBars().size())
                .serverTime(System.currentTimeMillis())
                .build();

        messagingTemplate.convertAndSend(topic, msg);
        log.debug("[BROADCAST] {} update: {} ({} bars) -> {}",
                updateType, sub.getCacheKey(), updatedBars.size(), topic);
    }

    /**
     * Anlik fiyat kotasyonunu {@code /topic/price/{symbol}} topic'ine yayinlar.
     *
     * <p>Ayni zamanda {@link QuotePriceCache} güncellemesi de yapilir.</p>
     *
     * @param quoteData fiyat verileri (symbol, last_price, change vb. iceren map)
     */
    public void broadcastQuoteUpdate(Map<String, Object> quoteData) {
        String symbol = (String) quoteData.get("symbol");
        if (symbol == null) return;
        String clean = cleanSymbol(symbol);
        quotePriceCache.update(clean, quoteData);
        String topic = "/topic/price/" + clean;
        messagingTemplate.convertAndSend(topic, quoteData);
        log.debug("[BROADCAST] Quote update: {} -> {}", clean, topic);
    }

    /**
     * Seans kapanisi mesajini tüm aktif topic'lere yayinlar.
     *
     * <p>{@code type="session_closed"} ve {@code marketOpen=false} iceren mesaj gönderir.
     * Frontend bu mesaji aldiginda "Seans Kapali" badge'ini gösterir.</p>
     *
     * @param activeTopics yayinlanacak STOMP topic path'leri
     */
    public void broadcastSessionClosed(Set<String> activeTopics) {
        for (String topic : activeTopics) {
            BarUpdateMessage msg = BarUpdateMessage.builder()
                    .type("session_closed")
                    .marketOpen(false)
                    .serverTime(System.currentTimeMillis())
                    .build();
            messagingTemplate.convertAndSend(topic, msg);
        }
        log.info("[BROADCAST] Seans kapanış mesajı gönderildi: {} topic", activeTopics.size());
    }

    private String buildTopic(String symbol, String period) {
        return "/topic/bars/" + cleanSymbol(symbol) + "/" + period;
    }

    private String cleanSymbol(String symbol) {
        if (symbol != null && symbol.contains(":")) {
            return symbol.substring(symbol.indexOf(":") + 1);
        }
        return symbol;
    }
}
