package com.scyborsa.api.service.chart;

import com.scyborsa.api.model.chart.CandleBar;
import com.scyborsa.api.model.chart.SymbolSubscription;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mum grafigi (candlestick) bar verilerini ve subscription bilgilerini bellekte tutan cache.
 *
 * <p>Her subscription bir {@code symbol:period} ciftine karsilik gelir (ör. "BIST:THYAO:1D").
 * Cache, TradingView WebSocket baglantisi üzerinden gelen bar verilerini saklar ve
 * subscriber sayisini takip eder.</p>
 *
 * <p>Thread-safe: {@link java.util.concurrent.ConcurrentHashMap} kullanir.
 * Subscription üzerindeki bar listesi degisiklikleri {@code synchronized} blok icinde yapilir.</p>
 *
 * <p>Maksimum bar sayisi: {@value #MAX_BARS}</p>
 *
 * @see SymbolSubscription
 * @see TradingViewBarService
 */
@Slf4j
@Component
public class BarCache {

    private static final int MAX_BARS = 1000;

    private final ConcurrentHashMap<String, SymbolSubscription> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sessionToKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> subscriberCounts = new ConcurrentHashMap<>();

    /**
     * Belirtilen sembol ve periyot icin cache'teki bar listesinin kopyasini döner.
     *
     * @param symbol hisse sembolü (ör. "BIST:THYAO")
     * @param period periyot (ör. "1D", "1H")
     * @return bar listesinin kopyasi; subscription yoksa {@code null}
     */
    public List<CandleBar> getBars(String symbol, String period) {
        String key = symbol + ":" + period;
        SymbolSubscription sub = cache.get(key);
        return sub != null ? new ArrayList<>(sub.getBars()) : null;
    }

    /**
     * Belirtilen sembol ve periyot icin subscription olup olmadigini kontrol eder.
     *
     * @param symbol hisse sembolü
     * @param period periyot
     * @return subscription varsa {@code true}
     */
    public boolean exists(String symbol, String period) {
        return cache.containsKey(symbol + ":" + period);
    }

    /**
     * Belirtilen sembol ve periyot icin subscription nesnesini döner.
     *
     * @param symbol hisse sembolü
     * @param period periyot
     * @return subscription nesnesi; yoksa {@code null}
     */
    public SymbolSubscription getSubscription(String symbol, String period) {
        return cache.get(symbol + ":" + period);
    }

    /**
     * TradingView chart session ID'sine göre subscription'i döner.
     *
     * @param chartSessionId TradingView chart session kimlik degeri
     * @return eslesen subscription; yoksa {@code null}
     */
    public SymbolSubscription getBySessionId(String chartSessionId) {
        String key = sessionToKey.get(chartSessionId);
        return key != null ? cache.get(key) : null;
    }

    /**
     * Yeni bir subscription olusturur ve cache'e ekler.
     *
     * <p>Ayni symbol:period icin önceki subscription varsa temizlenir.
     * Yeni subscription'in {@code initialLoadFuture} alani taze bir
     * {@link java.util.concurrent.CompletableFuture} ile baslatilir.</p>
     *
     * @param symbol         hisse sembolü (ör. "BIST:THYAO")
     * @param period         periyot (ör. "1D")
     * @param chartSessionId TradingView chart session ID'si
     * @param requestedBars  istenen bar sayisi
     * @return olusturulan yeni subscription nesnesi
     */
    public synchronized SymbolSubscription createSubscription(String symbol, String period, String chartSessionId, int requestedBars) {
        String key = symbol + ":" + period;

        SymbolSubscription old = cache.get(key);
        if (old != null) {
            sessionToKey.remove(old.getChartSessionId());
            if (old.getInitialLoadFuture() != null && !old.getInitialLoadFuture().isDone()) {
                old.getInitialLoadFuture().completeExceptionally(
                        new RuntimeException("Subscription yeniden oluşturuldu"));
            }
            log.info("Eski subscription temizlendi: {} (session: {})", key, old.getChartSessionId());
        }

        SymbolSubscription sub = new SymbolSubscription();
        sub.setSymbol(symbol);
        sub.setPeriod(period);
        sub.setChartSessionId(chartSessionId);
        sub.setRequestedBars(requestedBars);
        sub.setInitialLoadFuture(new CompletableFuture<>());
        sub.setLastUpdateTime(System.currentTimeMillis());

        cache.put(key, sub);
        sessionToKey.put(chartSessionId, key);

        log.info("Subscription oluşturuldu: {} (session: {})", key, chartSessionId);
        return sub;
    }

    /**
     * TradingView'den gelen ilk bar yüklemesini (initial load) isleer.
     *
     * <p>Subscription'in bar listesi tamamen yenilenir, streaming modu aktif edilir
     * ve bekleyen {@code initialLoadFuture} tamamlanir.</p>
     *
     * @param chartSessionId TradingView chart session ID'si
     * @param bars           yüklenen bar listesi
     */
    public void onInitialBars(String chartSessionId, List<CandleBar> bars) {
        SymbolSubscription sub = getBySessionId(chartSessionId);
        if (sub == null) {
            log.warn("Initial bars için subscription bulunamadı: {}", chartSessionId);
            return;
        }

        synchronized (sub) {
            sub.getBars().clear();
            sub.getBars().addAll(bars);
            sub.setInitialLoadDone(true);
            sub.setStreaming(true);
            sub.setLastUpdateTime(System.currentTimeMillis());

            if (sub.getInitialLoadFuture() != null && !sub.getInitialLoadFuture().isDone()) {
                sub.getInitialLoadFuture().complete(new ArrayList<>(bars));
            }
        }

        log.info("Initial bars yüklendi: {} ({} bar)", sub.getCacheKey(), bars.size());
    }

    /**
     * TradingView'den gelen bar güncellemelerini isleer.
     *
     * <p>Son bar'in timestamp'i eslesiyorsa güncellenir (mevcut mum kapanmadan önce),
     * eslesmiyorsa yeni bar eklenir. Maksimum bar sayisi ({@value #MAX_BARS}) asilirsa
     * en eski bar'lar silinir.</p>
     *
     * @param chartSessionId TradingView chart session ID'si
     * @param updatedBars    güncellenen bar'lar
     */
    public void onBarUpdate(String chartSessionId, List<CandleBar> updatedBars) {
        SymbolSubscription sub = getBySessionId(chartSessionId);
        if (sub == null) return;

        synchronized (sub) {
            List<CandleBar> existing = sub.getBars();
            for (CandleBar newBar : updatedBars) {
                if (!existing.isEmpty()) {
                    CandleBar lastBar = existing.get(existing.size() - 1);
                    if (lastBar.getTimestamp() == newBar.getTimestamp()) {
                        existing.set(existing.size() - 1, newBar);
                    } else {
                        existing.add(newBar);
                        while (existing.size() > MAX_BARS) {
                            existing.remove(0);
                        }
                    }
                } else {
                    existing.add(newBar);
                }
            }
            sub.setLastUpdateTime(System.currentTimeMillis());
        }
    }

    /**
     * Tamamlanmamis tüm {@code initialLoadFuture}'lari basarisiz olarak tamamlar.
     *
     * <p>WebSocket baglantisi koptuğunda bekleyen future'larin sonsuz bloklanmasini önler.</p>
     *
     * @param reason basarisizlik nedeni mesaji
     */
    public void failAllPendingFutures(String reason) {
        cache.values().forEach(sub -> {
            CompletableFuture<List<CandleBar>> future = sub.getInitialLoadFuture();
            if (future != null && !future.isDone()) {
                future.completeExceptionally(new RuntimeException(reason));
            }
        });
    }

    /**
     * Belirtilen sembol ve periyot icin subscriber sayisini bir arttirir.
     *
     * @param symbol hisse sembolü
     * @param period periyot
     * @return arttirma sonrasi yeni subscriber sayisi
     */
    public int incrementSubscribers(String symbol, String period) {
        String key = symbol + ":" + period;
        int count = subscriberCounts.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
        log.info("Subscriber eklendi: {} (toplam: {})", key, count);
        return count;
    }

    /**
     * Belirtilen sembol ve periyot icin subscriber sayisini bir azaltir.
     *
     * <p>Sayac sifira düserse tamamen kaldirilir.</p>
     *
     * @param symbol hisse sembolü
     * @param period periyot
     * @return azaltma sonrasi kalan subscriber sayisi (minimum 0)
     */
    public int decrementSubscribers(String symbol, String period) {
        String key = symbol + ":" + period;
        AtomicInteger counter = subscriberCounts.get(key);
        if (counter == null) return 0;
        int newCount = counter.decrementAndGet();
        if (newCount <= 0) {
            subscriberCounts.remove(key, counter);
            newCount = 0;
        }
        log.info("Subscriber çıktı: {} (kalan: {})", key, newCount);
        return newCount;
    }

    /**
     * Belirtilen sembol ve periyot icin mevcut subscriber sayisini döner.
     *
     * @param symbol hisse sembolü
     * @param period periyot
     * @return subscriber sayisi; sayac yoksa 0
     */
    public int getSubscriberCount(String symbol, String period) {
        String key = symbol + ":" + period;
        AtomicInteger counter = subscriberCounts.get(key);
        return counter != null ? Math.max(counter.get(), 0) : 0;
    }

    /**
     * Belirtilen sembol ve periyot icin subscription'i cache'ten kaldirir.
     *
     * <p>Session mapping ve subscriber sayaci da temizlenir.</p>
     *
     * @param symbol hisse sembolü
     * @param period periyot
     */
    public void removeSubscription(String symbol, String period) {
        String key = symbol + ":" + period;
        SymbolSubscription sub = cache.remove(key);
        if (sub != null) {
            sessionToKey.remove(sub.getChartSessionId());
            subscriberCounts.remove(key);
            log.info("Subscription kaldırıldı: {}", key);
        }
    }

    /**
     * TradingView chart session ID'sine göre subscription'i cache'ten kaldirir.
     *
     * @param chartSessionId TradingView chart session kimlik degeri
     */
    public void removeBySessionId(String chartSessionId) {
        String key = sessionToKey.remove(chartSessionId);
        if (key != null) {
            cache.remove(key);
            subscriberCounts.remove(key);
            log.info("Subscription kaldırıldı (session): {}", chartSessionId);
        }
    }

    /**
     * Tüm subscription'lari, session mapping'leri ve subscriber sayaclarini temizler.
     */
    public void clearAll() {
        cache.clear();
        sessionToKey.clear();
        subscriberCounts.clear();
        log.info("Tüm cache temizlendi");
    }

    /**
     * Cache'in anlik durum bilgisini döner (debug/monitoring amacli).
     *
     * <p>Dönen map icerigi: totalSubscriptions, activeSessions ve
     * her subscription icin detay bilgileri (key, barCount, streaming, subscribers vb.).</p>
     *
     * @return cache durum bilgisi iceren map
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("totalSubscriptions", cache.size());
        status.put("activeSessions", sessionToKey.size());

        List<Map<String, Object>> subs = new ArrayList<>();
        cache.forEach((key, sub) -> {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("key", key);
            info.put("barCount", sub.getBars().size());
            info.put("streaming", sub.isStreaming());
            info.put("initialLoadDone", sub.isInitialLoadDone());
            info.put("lastUpdate", sub.getLastUpdateTime());
            AtomicInteger sc = subscriberCounts.get(key);
            info.put("subscribers", sc != null ? sc.get() : 0);
            subs.add(info);
        });
        status.put("subscriptions", subs);
        return status;
    }

    /**
     * Tüm aktif subscription'larin koleksiyonunu döner.
     *
     * @return cache'teki tüm subscription nesneleri
     */
    public Collection<SymbolSubscription> getAllSubscriptions() {
        return cache.values();
    }
}
