package com.scyborsa.api.service.chart;

import com.scyborsa.api.service.alert.PriceAlertEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anlik fiyat kotasyonlarini (quote) bellekte tutan cache.
 *
 * <p>TradingView WebSocket'ten gelen fiyat verilerini sembol bazinda saklar.
 * {@link #waitForQuote(String, long)} metodu ile henüz gelmemis bir
 * kotasyonun beklenmesi desteklenir (asenkron).</p>
 *
 * <p>Thread-safe: {@link java.util.concurrent.ConcurrentHashMap} kullanir.</p>
 *
 * @see BarBroadcastService
 */
@Slf4j
@Component
public class QuotePriceCache {

    /** Fiyat alarm motoru — mevcut degilse null (graceful degradation). */
    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private PriceAlertEngine alertEngine;

    /** Sembol bazinda fiyat kotasyonlarinin tutuldugu thread-safe cache. */
    private final ConcurrentHashMap<String, QuoteEntry> quotes = new ConcurrentHashMap<>();

    /**
     * Belirtilen sembol icin fiyat kotasyonunu günceller.
     *
     * <p>Eger bekleyen bir {@code pendingFuture} varsa tamamlanir.</p>
     *
     * @param symbol  hisse sembolü (ör. "THYAO")
     * @param qsdData TradingView QSD (quote session data) verisi
     */
    public void update(String symbol, Map<String, Object> qsdData) {
        QuoteEntry entry = quotes.get(symbol);
        if (entry != null) {
            entry.data = qsdData;
            entry.lastUpdateTime = System.currentTimeMillis();
            CompletableFuture<Map<String, Object>> pending = entry.pendingFuture;
            if (pending != null && !pending.isDone()) {
                pending.complete(qsdData);
                entry.pendingFuture = null;
            }
        } else {
            quotes.put(symbol, new QuoteEntry(qsdData, System.currentTimeMillis()));
        }

        // Fiyat alarm motoru kontrolu
        if (alertEngine != null) {
            Object lp = qsdData.get("lp");
            if (lp instanceof Number) {
                String stockCode = symbol.replace("BIST:", "");
                alertEngine.checkPrice(stockCode, ((Number) lp).doubleValue());
            }
        }
    }

    /**
     * Belirtilen sembol icin cache'teki son fiyat kotasyonunu döner.
     *
     * @param symbol hisse sembolü
     * @return fiyat verileri iceren map; cache'te yoksa {@code null}
     */
    public Map<String, Object> get(String symbol) {
        QuoteEntry entry = quotes.get(symbol);
        return entry != null ? entry.data : null;
    }

    /**
     * Belirtilen sembol icin fiyat kotasyonunu asenkron olarak bekler.
     *
     * <p>Cache'te veri varsa aninda tamamlanmis future döner.
     * Yoksa belirtilen süre kadar bekler; süre dolarsa {@code null} ile tamamlanir.</p>
     *
     * @param symbol    hisse sembolü
     * @param timeoutMs maksimum bekleme süresi (milisaniye)
     * @return fiyat verileri iceren future; timeout durumunda {@code null} ile tamamlanir
     */
    public CompletableFuture<Map<String, Object>> waitForQuote(String symbol, long timeoutMs) {
        QuoteEntry entry = quotes.get(symbol);
        if (entry != null && entry.data != null) {
            return CompletableFuture.completedFuture(entry.data);
        }

        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        QuoteEntry newEntry = new QuoteEntry(null, 0);
        newEntry.pendingFuture = future;

        QuoteEntry existing = quotes.putIfAbsent(symbol, newEntry);
        if (existing != null && existing.data != null) {
            return CompletableFuture.completedFuture(existing.data);
        }
        if (existing != null) {
            existing.pendingFuture = future;
        }

        CompletableFuture.delayedExecutor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .execute(() -> {
                    if (!future.isDone()) {
                        future.complete(null);
                    }
                });

        return future;
    }

    /**
     * Tek bir sembolun fiyat kotasyonu verilerini tutan ic sinif.
     * Volatile alanlar thread-safe erisim saglar.
     */
    static class QuoteEntry {
        /** Son alinan fiyat kotasyonu verisi (lp, ch, chp vb.). */
        volatile Map<String, Object> data;
        /** Son guncelleme zamani (epoch milisaniye). */
        volatile long lastUpdateTime;
        /** Henuz gelmemis kotasyon icin bekleyen asenkron future. */
        volatile CompletableFuture<Map<String, Object>> pendingFuture;

        QuoteEntry(Map<String, Object> data, long lastUpdateTime) {
            this.data = data;
            this.lastUpdateTime = lastUpdateTime;
        }
    }
}
