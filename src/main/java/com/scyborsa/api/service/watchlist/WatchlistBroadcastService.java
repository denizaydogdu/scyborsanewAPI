package com.scyborsa.api.service.watchlist;

import com.scyborsa.api.dto.watchlist.WatchlistPriceUpdateDto;
import com.scyborsa.api.repository.WatchlistItemRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Takip listesi anlik fiyat yayini servisi.
 *
 * <p>Aktif takip listelerindeki hisseleri bellek-ici indeksler ve her fiyat
 * guncellemesinde ilgili kullanicilara STOMP WebSocket uzerinden anlik
 * fiyat bildirimlerini gonderir.</p>
 *
 * <p>Pattern: {@link com.scyborsa.api.service.alert.PriceAlertEngine} ile ayni
 * — {@link ConcurrentHashMap} in-memory index, {@code @PostConstruct} DB load,
 * QuotePriceCache hook, STOMP per-user broadcast.</p>
 *
 * <p>Thread-safe: {@link ConcurrentHashMap} + {@link java.util.Collections#synchronizedSet} kullanir.</p>
 *
 * @see com.scyborsa.api.service.chart.QuotePriceCache
 * @see com.scyborsa.api.service.chart.TradingViewBarService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchlistBroadcastService {

    private final WatchlistItemRepository watchlistItemRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /** TradingView WebSocket servisi — watchlist hisseleri icin canli fiyat aboneligi. */
    @Autowired(required = false)
    @Lazy
    private com.scyborsa.api.service.chart.TradingViewBarService tradingViewBarService;

    /** Hisse kodu bazinda izleyen kullanici indeksi (stockCode -> Set<userEmail>). */
    private final ConcurrentHashMap<String, Set<String>> stockToUsers = new ConcurrentHashMap<>();

    /** Kullanici bazinda izlenen hisse indeksi — ters indeks (userEmail -> Set<stockCode>). */
    private final ConcurrentHashMap<String, Set<String>> userToStocks = new ConcurrentHashMap<>();

    /** Throttle haritasi — "stockCode:email" -> son broadcast zamani (epoch ms). */
    private final ConcurrentHashMap<String, Long> lastBroadcastTime = new ConcurrentHashMap<>();

    /** Her hisse:kullanici cifti icin minimum broadcast araligi (ms). */
    private static final long THROTTLE_INTERVAL_MS = 2000;

    /** Ilk yuklemenin tamamlanip tamamlanmadigini belirtir. */
    private volatile boolean initialized = false;

    /**
     * Uygulama baslandiginda aktif takip listelerindeki hisseleri DB'den yukler.
     *
     * <p>Tum aktif watchlist item'lari icin stockCode -> userEmail indeksleri
     * olusturulur ve TradingView WebSocket'e subscribe edilir.</p>
     */
    @PostConstruct
    public void init() {
        try {
            Set<String> allStockCodes = watchlistItemRepository.findAllDistinctActiveStockCodes();

            for (String stockCode : allStockCodes) {
                Set<String> users = watchlistItemRepository.findUserEmailsByStockCode(stockCode);
                if (!users.isEmpty()) {
                    stockToUsers.put(stockCode.toUpperCase(), ConcurrentHashMap.newKeySet());
                    stockToUsers.get(stockCode.toUpperCase()).addAll(users);

                    for (String email : users) {
                        userToStocks.computeIfAbsent(email, k -> ConcurrentHashMap.newKeySet())
                                .add(stockCode.toUpperCase());
                    }
                }
            }

            initialized = true;
            log.info("[WATCHLIST-BROADCAST] Init tamamlandi: {} hisse, {} kullanici",
                    stockToUsers.size(), userToStocks.size());

            // Takip listesindeki hisseleri TradingView WebSocket'e subscribe et
            subscribeAllActiveStocks();
        } catch (Exception e) {
            log.error("[WATCHLIST-BROADCAST] Init hatasi", e);
            initialized = true; // Hata olsa da servisi baslatarak yeni eklemelere izin ver
        }
    }

    /**
     * QuotePriceCache'ten gelen fiyat guncellemesini isler.
     *
     * <p>Hisse kodunu izleyen kullanicilar varsa throttle kontrolu yapilir
     * ve per-user STOMP broadcast gonderilir. Broadcast islemleri async
     * olarak yapilir — quote update thread'i bloklanmaz.</p>
     *
     * @param stockCode hisse kodu (orn. "THYAO")
     * @param qsdData   TradingView QSD (quote session data) verisi
     */
    public void onQuoteUpdate(String stockCode, Map<String, Object> qsdData) {
        if (!initialized) {
            return;
        }

        String key = stockCode.toUpperCase();
        Set<String> users = stockToUsers.get(key);
        if (users == null || users.isEmpty()) {
            return;
        }

        WatchlistPriceUpdateDto dto = buildDto(stockCode, qsdData);

        // Snapshot — ConcurrentModificationException onlemi
        Set<String> userSnapshot = new HashSet<>(users);

        // Async broadcast — quote update thread'i bloklama
        CompletableFuture.runAsync(() -> {
            long now = System.currentTimeMillis();
            for (String email : userSnapshot) {
                String throttleKey = key + ":" + email;
                Long lastTime = lastBroadcastTime.get(throttleKey);
                if (lastTime != null && (now - lastTime) < THROTTLE_INTERVAL_MS) {
                    continue;
                }
                lastBroadcastTime.put(throttleKey, now);

                try {
                    messagingTemplate.convertAndSendToUser(email, "/queue/watchlist", dto);
                } catch (Exception e) {
                    log.warn("[WATCHLIST-BROADCAST] STOMP gonderim hatasi: {} -> {}", stockCode, email, e);
                }
            }
        });
    }

    /**
     * Yeni hisse:kullanici ciftini indekse ekler.
     *
     * <p>Eger hisse icin ilk izleyici ise TradingView WebSocket'e subscribe edilir.</p>
     *
     * @param stockCode hisse kodu (orn. "THYAO")
     * @param userEmail kullanici email adresi
     */
    public void addSubscription(String stockCode, String userEmail) {
        String key = stockCode.toUpperCase();
        boolean[] isFirst = {false};

        stockToUsers.compute(key, (k, existing) -> {
            if (existing == null) {
                isFirst[0] = true;
                existing = ConcurrentHashMap.newKeySet();
            }
            existing.add(userEmail);
            return existing;
        });

        userToStocks.computeIfAbsent(userEmail, k -> ConcurrentHashMap.newKeySet())
                .add(key);

        // WS subscribe lock disinda — blocking I/O ConcurrentHashMap lock altinda yapilmaz
        if (isFirst[0]) {
            subscribeToQuote(key);
        }

        log.debug("[WATCHLIST-BROADCAST] Subscription eklendi: {} -> {}", stockCode, userEmail);
    }

    /**
     * Hisse:kullanici ciftini indeksten cikarir.
     *
     * @param stockCode hisse kodu (orn. "THYAO")
     * @param userEmail kullanici email adresi
     */
    public void removeSubscription(String stockCode, String userEmail) {
        String key = stockCode.toUpperCase();

        stockToUsers.computeIfPresent(key, (k, users) -> {
            users.remove(userEmail);
            return users.isEmpty() ? null : users;
        });

        userToStocks.computeIfPresent(userEmail, (k, stocks) -> {
            stocks.remove(key);
            return stocks.isEmpty() ? null : stocks;
        });

        // Throttle entry temizle
        lastBroadcastTime.remove(key + ":" + userEmail);

        log.debug("[WATCHLIST-BROADCAST] Subscription cikarildi: {} -> {}", stockCode, userEmail);
    }

    /**
     * Kullanicinin tum hisse aboneliklerini toplu olarak cikarir.
     *
     * <p>Ters indeks ({@code userToStocks}) kullanilarak kullanicinin
     * tum hisselerinden cikarilir ve ilgili throttle entry'leri temizlenir.</p>
     *
     * @param userEmail kullanici email adresi
     */
    public void removeAllUserStocks(String userEmail) {
        Set<String> stocks = userToStocks.remove(userEmail);
        if (stocks == null || stocks.isEmpty()) {
            return;
        }

        for (String stockCode : stocks) {
            stockToUsers.computeIfPresent(stockCode, (k, users) -> {
                users.remove(userEmail);
                return users.isEmpty() ? null : users;
            });
            lastBroadcastTime.remove(stockCode + ":" + userEmail);
        }

        log.debug("[WATCHLIST-BROADCAST] Kullanici tum aboneliklerden cikarildi: {}, {} hisse", userEmail, stocks.size());
    }

    /**
     * Aktif olarak izlenen tum hisse kodlarini doner (reconnect icin).
     *
     * @return aktif izlenen hisse kodlari seti
     */
    public Set<String> getActiveStockCodes() {
        return new HashSet<>(stockToUsers.keySet());
    }

    /**
     * Throttle haritasindaki 5 dakikadan eski entry'leri temizler.
     *
     * <p>Her 5 dakikada bir calisir. Bellek sizmasi onlemi.</p>
     */
    @Scheduled(fixedDelay = 300000)
    public void cleanupThrottleMap() {
        if (lastBroadcastTime.isEmpty()) {
            return;
        }

        long threshold = System.currentTimeMillis() - (5 * 60 * 1000);
        int before = lastBroadcastTime.size();
        lastBroadcastTime.entrySet().removeIf(entry -> entry.getValue() < threshold);
        int removed = before - lastBroadcastTime.size();

        if (removed > 0) {
            log.debug("[WATCHLIST-BROADCAST] Throttle temizligi: {} entry silindi, {} kaldi",
                    removed, lastBroadcastTime.size());
        }
    }

    // ==================== PRIVATE HELPERS ====================

    /**
     * QSD verisinden WatchlistPriceUpdateDto olusturur.
     *
     * @param stockCode hisse kodu
     * @param qsdData   TradingView QSD verisi
     * @return broadcast icin DTO
     */
    private WatchlistPriceUpdateDto buildDto(String stockCode, Map<String, Object> qsdData) {
        return WatchlistPriceUpdateDto.builder()
                .stockCode(stockCode.toUpperCase())
                .lastPrice(toDouble(qsdData.get("lp")))
                .change(toDouble(qsdData.get("ch")))
                .changePercent(toDouble(qsdData.get("chp")))
                .volume(toDouble(qsdData.get("volume")))
                .updateTime(System.currentTimeMillis())
                .build();
    }

    /**
     * Object degerini guvenli sekilde Double'a donusturur.
     *
     * @param val donusturulecek deger
     * @return Double degeri; null veya Number degilse {@code null}
     */
    private Double toDouble(Object val) {
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        }
        return null;
    }

    /**
     * Belirtilen hisse kodunu TradingView WebSocket'e canli fiyat aboneligi olarak ekler.
     *
     * @param stockCode hisse kodu (orn. "THYAO")
     */
    private void subscribeToQuote(String stockCode) {
        if (tradingViewBarService != null) {
            try {
                tradingViewBarService.subscribeQuote("BIST:" + stockCode);
                log.info("[WATCHLIST-BROADCAST] WS quote subscribe: {}", stockCode);
            } catch (Exception e) {
                log.warn("[WATCHLIST-BROADCAST] WS quote subscribe basarisiz: {}", stockCode, e);
            }
        }
    }

    /**
     * Tum aktif izlenen hisseleri TradingView WebSocket'e subscribe eder.
     * Uygulama baslangicinda cagrilir.
     */
    private void subscribeAllActiveStocks() {
        if (tradingViewBarService == null || stockToUsers.isEmpty()) {
            return;
        }
        try {
            for (String stockCode : stockToUsers.keySet()) {
                subscribeToQuote(stockCode);
            }
            log.info("[WATCHLIST-BROADCAST] {} hisse WS quote subscribe edildi", stockToUsers.size());
        } catch (Exception e) {
            log.warn("[WATCHLIST-BROADCAST] Toplu WS subscribe basarisiz", e);
        }
    }
}
