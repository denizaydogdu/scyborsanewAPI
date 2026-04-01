package com.scyborsa.api.service.chart;

import com.scyborsa.api.config.TradingViewConfig;
import com.scyborsa.api.constants.TradingViewConstant;
import com.scyborsa.api.model.chart.CandleBar;
import com.scyborsa.api.model.chart.SymbolSubscription;
import com.scyborsa.api.service.market.IndexPerformanceService;
import com.scyborsa.api.utils.BistTradingCalendar;
import com.scyborsa.api.websocket.TradingViewBarWebSocketClient;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * TradingView WebSocket üzerinden mum grafigi (candlestick) bar verilerini yöneten ana servis.
 *
 * <p>Sorumluluklar:</p>
 * <ul>
 *   <li>TradingView WebSocket baglantisini yönetme (connect/disconnect/reconnect)</li>
 *   <li>Sembol+periyot bazinda bar subscription'lari olusturma ve yönetme</li>
 *   <li>Subscriber sayisi takibi (son subscriber cikarsa subscription kapatilir)</li>
 *   <li>Sagllik kontrolü (30sn) ve stale subscription temizligi (10dk)</li>
 *   <li>Fiyat kotasyonu (quote) subscription'lari</li>
 * </ul>
 *
 * <p>Bagimliliklar:</p>
 * <ul>
 *   <li>{@link com.scyborsa.api.config.TradingViewConfig} - WebSocket URL ve cookie</li>
 *   <li>{@link BarCache} - Bar verisi ve subscription cache'i</li>
 *   <li>{@link BarBroadcastService} - WebSocket yayini</li>
 * </ul>
 *
 * @see com.scyborsa.api.websocket.TradingViewBarWebSocketClient
 * @see BarCache
 */
@Slf4j
@Service
public class TradingViewBarService {

    /** TradingView WebSocket konfigurasyonu (URL, cookie, auth token). */
    private final TradingViewConfig config;
    /** Bar verisi ve subscription bilgilerinin tutuldugu in-memory cache. */
    private final BarCache barCache;
    /** Bar ve quote guncellemelerinin istemcilere yayinlandigi servis. */
    private final BarBroadcastService broadcastService;

    /** Aktif TradingView WebSocket istemci instance'i (volatile — thread-safe erisim). */
    private volatile TradingViewBarWebSocketClient wsClient;

    /** Fiyat alarmi motoru — reconnect sonrasi alarm hisselerini yeniden subscribe etmek icin. */
    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.scyborsa.api.service.alert.PriceAlertEngine priceAlertEngine;

    /** Takip listesi broadcast servisi — reconnect sonrasi watchlist hisselerini yeniden subscribe etmek icin. */
    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.scyborsa.api.service.watchlist.WatchlistBroadcastService watchlistBroadcastService;

    /** Seans açık→kapalı geçişini tespit etmek için önceki durum. */
    private volatile boolean marketWasOpen = false;

    /**
     * Constructor injection ile bagimliliklari alir.
     *
     * @param config           TradingView konfigürasyonu (WS URL, cookie)
     * @param barCache         bar verisi cache'i
     * @param broadcastService WebSocket yayini servisi
     */
    public TradingViewBarService(TradingViewConfig config, BarCache barCache,
                                  BarBroadcastService broadcastService) {
        this.config = config;
        this.barCache = barCache;
        this.broadcastService = broadcastService;
    }

    /**
     * TradingView WebSocket sunucusuna baglanir.
     *
     * <p>Zaten bagli ise islem yapmaz. Baglanti sirasinda konfigüre edilmis
     * cookie ve User-Agent header'lari eklenir.</p>
     */
    public synchronized void connect() {
        if (wsClient != null && wsClient.isConnected()) {
            log.info("[BAR-SERVICE] Zaten bağlı");
            return;
        }

        try {
            String dateStr = new SimpleDateFormat("yyyy_MM_dd-HH_mm").format(new Date());
            String wsUrl = TradingViewConstant.WEBSOCKET_URL + "?from=chart%2F&date=" + dateStr;

            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", TradingViewConfig.DEFAULT_USER_AGENT);
            headers.put("Origin", config.getHeadersOrigin());
            if (config.getWebsocketCookieValue() != null && !config.getWebsocketCookieValue().isEmpty()) {
                headers.put("Cookie", config.getWebsocketCookieValue());
            }

            wsClient = new TradingViewBarWebSocketClient(
                    new URI(wsUrl), headers, config, barCache, broadcastService);

            wsClient.connect();
            log.info("[BAR-SERVICE] WebSocket bağlantısı başlatıldı: {}", wsUrl);
        } catch (Exception e) {
            log.error("[BAR-SERVICE] WebSocket bağlantı hatası: {}", e.getMessage(), e);
        }
    }

    /**
     * TradingView WebSocket baglantisini kapatir.
     */
    public synchronized void disconnect() {
        if (wsClient != null) {
            try {
                wsClient.close();
            } catch (Exception e) {
                log.warn("[BAR-SERVICE] Disconnect hatası: {}", e.getMessage());
            }
            wsClient = null;
            log.info("[BAR-SERVICE] WebSocket bağlantısı kapatıldı");
        }
    }

    /**
     * WebSocket baglantisinin aktif olup olmadigini kontrol eder.
     *
     * @return baglanti aktifse {@code true}
     */
    public boolean isConnected() {
        return wsClient != null && wsClient.isConnected();
    }

    /**
     * Belirtilen sembol ve periyot icin bar verilerini asenkron olarak döner.
     *
     * <p>Öncelik sirasi:</p>
     * <ol>
     *   <li>Cache'te veri varsa aninda döner</li>
     *   <li>Bekleyen subscription varsa onun future'ini döner</li>
     *   <li>Yeni subscription baslatir (gerekirse WebSocket baglantisi olusturur)</li>
     * </ol>
     *
     * @param symbol hisse sembolü (ör. "THYAO" veya "BIST:THYAO")
     * @param period periyot (ör. "1D", "1H", "15")
     * @param bars   istenen bar sayisi
     * @return bar listesi iceren future; hata durumunda failed future
     */
    public CompletableFuture<List<CandleBar>> getBars(String symbol, String period, int bars) {
        String symbolFull = symbol.contains(":") ? symbol : "BIST:" + symbol;

        // 1) Cache varsa → market açık/kapalı farketmez, anında dön
        List<CandleBar> cached = barCache.getBars(symbolFull, period);
        if (cached != null && !cached.isEmpty()) {
            return CompletableFuture.completedFuture(cached);
        }

        // 2) Bekleyen subscription varsa onun future'ini dön
        SymbolSubscription existing = barCache.getSubscription(symbolFull, period);
        if (existing != null && existing.getInitialLoadFuture() != null) {
            return existing.getInitialLoadFuture();
        }

        // 3) Cache yok — market kapalıysa one-shot fetch (live subscription açma)
        if (!BistTradingCalendar.isMarketOpen()) {
            log.info("[BAR-SERVICE] Seans kapalı, one-shot fetch: {}:{}", symbolFull, period);
            return fetchOneShotBars(symbolFull, period, bars);
        }

        // 4) Cache yok + market açık → normal subscription akışı
        // synchronized sadece connect + subscribe state mutation için
        TradingViewBarWebSocketClient client;
        synchronized (this) {
            if (!isConnected()) {
                connect();
            }
            client = this.wsClient;
        }

        if (client == null) {
            return CompletableFuture.failedFuture(new RuntimeException("WebSocket bağlanamadı"));
        }

        if (client.isConnected()) {
            client.subscribeToBar(symbolFull, period, bars);
            SymbolSubscription sub = barCache.getSubscription(symbolFull, period);
            if (sub != null && sub.getInitialLoadFuture() != null) {
                return sub.getInitialLoadFuture();
            }
            return CompletableFuture.failedFuture(new RuntimeException("Subscription oluşturulamadı"));
        }

        // Bağlantı henüz hazır değil — async bekle (lock TUTULMAZ, deadlock riski yok)
        CompletableFuture<List<CandleBar>> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try {
                for (int i = 0; i < 50 && !client.isConnected(); i++) {
                    Thread.sleep(config.getChartWebsocketWaitMs());
                }
                if (client.isConnected()) {
                    client.subscribeToBar(symbolFull, period, bars);
                    SymbolSubscription sub = barCache.getSubscription(symbolFull, period);
                    if (sub != null && sub.getInitialLoadFuture() != null) {
                        sub.getInitialLoadFuture().whenComplete((result, ex) -> {
                            if (ex != null) future.completeExceptionally(ex);
                            else future.complete(result);
                        });
                    } else {
                        future.completeExceptionally(new RuntimeException("Subscription oluşturulamadı"));
                    }
                } else {
                    future.completeExceptionally(new RuntimeException("WebSocket bağlantı zaman aşımı"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Cache'teki bar verilerini senkron olarak döner (WebSocket istegi baslatmaz).
     *
     * @param symbol hisse sembolü (ör. "THYAO" veya "BIST:THYAO")
     * @param period periyot
     * @return cache'teki bar listesi; yoksa {@code null}
     */
    public List<CandleBar> getCachedBars(String symbol, String period) {
        String symbolFull = symbol.contains(":") ? symbol : "BIST:" + symbol;
        return barCache.getBars(symbolFull, period);
    }

    /**
     * Belirtilen sembol ve periyot icin subscriber ekler.
     *
     * @param symbol hisse sembolü
     * @param period periyot
     */
    public void addSubscriber(String symbol, String period) {
        String symbolFull = symbol.contains(":") ? symbol : "BIST:" + symbol;
        barCache.incrementSubscribers(symbolFull, period);
    }

    /**
     * Belirtilen sembol ve periyot icin subscriber cikarir.
     *
     * <p>Son subscriber cikarsa TradingView tarafindaki subscription da kapatilir
     * ve cache temizlenir.</p>
     *
     * @param symbol hisse sembolü
     * @param period periyot
     */
    public void removeSubscriber(String symbol, String period) {
        String symbolFull = symbol.contains(":") ? symbol : "BIST:" + symbol;
        int remaining = barCache.decrementSubscribers(symbolFull, period);
        if (remaining <= 0) {
            log.info("[BAR-SERVICE] Son subscriber çıktı, TradingView subscription kapatılıyor: {}:{}", symbolFull, period);
            if (wsClient != null && wsClient.isConnected()) {
                wsClient.unsubscribeFromBar(symbolFull, period);
            } else {
                barCache.removeSubscription(symbolFull, period);
            }
        }
    }

    /**
     * Uygulama baslatildiktan sonra endeks sembollerini WS'e subscribe eder.
     *
     * <p>{@code ApplicationReadyEvent} tum bean'ler ve embedded server hazir olduktan
     * sonra atesler. {@code @Async} ile ayri thread'de calisir — main thread'i bloklamaz.
     * PriceAlertEngine.subscribeOnStartup() ile ayni pattern.</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void subscribeIndexQuotesOnStartup() {
        log.info("[BAR-SERVICE] Startup endeks WS subscribe basladi (ApplicationReadyEvent)");
        try {
            if (!isConnected()) {
                connect();
            }

            // Baglanti hazir olmasini bekle
            TradingViewBarWebSocketClient client = this.wsClient;
            if (client != null) {
                for (int i = 0; i < 25 && !client.isConnected(); i++) {
                    Thread.sleep(config.getChartWebsocketWaitMs());
                }
                if (client.isConnected()) {
                    subscribeIndexQuotes();
                } else {
                    log.warn("[BAR-SERVICE] Startup endeks subscribe — WS baglanti zaman asimi");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[BAR-SERVICE] Startup endeks subscribe kesintiye ugradi");
        } catch (Exception e) {
            log.warn("[BAR-SERVICE] Startup endeks subscribe hatasi: {}", e.getMessage());
        }
    }

    /**
     * WebSocket baglantisinin saglik kontrolünü yapar (her 30 saniyede bir).
     *
     * <p>Baglanti kopuk ve aktif subscription varsa otomatik reconnect ve
     * resubscribe islemini baslatir.</p>
     */
    @Scheduled(fixedDelay = 30000)
    public void healthCheck() {
        if (!isConnected() && !barCache.getAllSubscriptions().isEmpty()) {
            log.warn("[BAR-SERVICE] Bağlantı kopuk, {} aktif subscription var. Reconnect...",
                    barCache.getAllSubscriptions().size());
            connect();

            TradingViewBarWebSocketClient client = this.wsClient;
            if (client != null) {
                CompletableFuture.runAsync(() -> {
                    try {
                        for (int i = 0; i < 25 && !client.isConnected(); i++) {
                            Thread.sleep(config.getChartWebsocketWaitMs());
                        }
                        if (client.isConnected()) {
                            resubscribeAll();
                            resubscribeAlarmQuotes();
                            resubscribeWatchlistQuotes();
                            subscribeIndexQuotes();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        }
    }

    /**
     * Eski (stale) subscription'lari temizler (her 10 dakikada bir).
     *
     * <p>Subscriber'i olan ancak 30 dakikadan uzun süredir güncellenmemis
     * subscription'lar kapatilir ve cache'ten kaldirilir.</p>
     */
    @Scheduled(fixedDelay = 600000)
    public void cleanupStaleSubscriptions() {
        long nowMs = System.currentTimeMillis();
        long staleThreshold = 30 * 60 * 1000;

        List<SymbolSubscription> snapshot = new ArrayList<>(barCache.getAllSubscriptions());
        for (SymbolSubscription sub : snapshot) {
            int subs = barCache.getSubscriberCount(sub.getSymbol(), sub.getPeriod());
            long age = nowMs - sub.getLastUpdateTime();

            if (subs > 0 && age > staleThreshold) {
                log.warn("[BAR-SERVICE] Stale subscription temizleniyor: {} subscribers={} lastUpdate={}dk önce",
                        sub.getCacheKey(), subs, age / 60000);
                if (wsClient != null && wsClient.isConnected()) {
                    wsClient.unsubscribeFromBar(sub.getSymbol(), sub.getPeriod());
                } else {
                    barCache.removeSubscription(sub.getSymbol(), sub.getPeriod());
                }
            }
        }
    }

    private void resubscribeAll() {
        TradingViewBarWebSocketClient client = this.wsClient;
        if (client == null || !client.isConnected()) {
            log.warn("[BAR-SERVICE] Resubscribe atlandı — client null veya bağlı değil");
            return;
        }

        List<SymbolSubscription> snapshot = new ArrayList<>(barCache.getAllSubscriptions());
        for (SymbolSubscription sub : snapshot) {
            log.info("[BAR-SERVICE] Resubscribe: {} period={}", sub.getSymbol(), sub.getPeriod());
            String oldSession = sub.getChartSessionId();
            barCache.removeBySessionId(oldSession);
            client.subscribeToBar(sub.getSymbol(), sub.getPeriod(), sub.getRequestedBars());
        }
    }

    /**
     * Takip listesi hisselerini TradingView'e yeniden abone eder.
     */
    private void resubscribeWatchlistQuotes() {
        if (watchlistBroadcastService == null) {
            return;
        }
        Set<String> watchlistStocks = watchlistBroadcastService.getActiveStockCodes();
        for (String stockCode : watchlistStocks) {
            try {
                subscribeQuote("BIST:" + stockCode);
            } catch (Exception e) {
                log.warn("[BAR-SERVICE] Watchlist quote re-subscribe basarisiz: {}", stockCode);
            }
        }
        if (!watchlistStocks.isEmpty()) {
            log.info("[BAR-SERVICE] {} watchlist hissesi WS re-subscribe edildi", watchlistStocks.size());
        }
    }

    /**
     * Reconnect sonrasi alarm hisselerini WS'e yeniden subscribe eder.
     */
    private void resubscribeAlarmQuotes() {
        if (priceAlertEngine == null) {
            return;
        }
        Set<String> alertStocks = priceAlertEngine.getActiveStockCodes();
        for (String stockCode : alertStocks) {
            try {
                subscribeQuote("BIST:" + stockCode);
            } catch (Exception e) {
                log.warn("[BAR-SERVICE] Alarm quote re-subscribe basarisiz: {}", stockCode);
            }
        }
        if (!alertStocks.isEmpty()) {
            log.info("[BAR-SERVICE] {} alarm hissesi WS re-subscribe edildi", alertStocks.size());
        }
    }

    /**
     * 35 BIST endeks sembolunu TradingView WebSocket'e quote-only olarak subscribe eder.
     *
     * <p>Endeks sembolleri {@link IndexPerformanceService#INDEX_SYMBOLS} listesinden alinir.
     * QuotePriceCache'e duser, IndexPerformanceService oradan okur.</p>
     *
     * <p>Startup, reconnect ve seans acilis gecislerinde cagirilir.</p>
     */
    public void subscribeIndexQuotes() {
        TradingViewBarWebSocketClient client = this.wsClient;
        if (client == null || !client.isConnected()) {
            log.warn("[BAR-SERVICE] Endeks quote subscribe atlandı — client null veya bağlı değil");
            return;
        }

        int count = 0;
        for (String symbol : IndexPerformanceService.INDEX_SYMBOLS) {
            try {
                client.subscribeQuoteOnly("BIST:" + symbol);
                count++;
            } catch (Exception e) {
                log.warn("[BAR-SERVICE] Endeks quote subscribe basarisiz: {}", symbol);
            }
        }
        log.info("[BAR-SERVICE] {} endeks sembolü WS quote subscription'a eklendi", count);
    }

    /**
     * Belirtilen sembol icin fiyat kotasyonu (quote-only) subscription'i baslatir.
     *
     * <p>Gerekirse önce WebSocket baglantisi kurulur. Baglanti henüz hazir degilse
     * asenkron olarak beklenir.</p>
     *
     * @param symbol hisse sembolü (ör. "THYAO" veya "BIST:THYAO")
     */
    public void subscribeQuote(String symbol) {
        String symbolFull = symbol.contains(":") ? symbol : "BIST:" + symbol;

        if (!isConnected()) {
            connect();
        }

        TradingViewBarWebSocketClient client = this.wsClient;
        if (client == null) {
            log.warn("[BAR-SERVICE] WS client null, quote subscribe edilemedi: {}", symbolFull);
            return;
        }

        if (client.isConnected()) {
            client.subscribeQuoteOnly(symbolFull);
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                for (int i = 0; i < 25 && !client.isConnected(); i++) {
                    Thread.sleep(config.getChartWebsocketWaitMs());
                }
                if (client.isConnected()) {
                    client.subscribeQuoteOnly(symbolFull);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Seans kapaliyken one-shot TradingView fetch yapar: bağlan → veri çek → cache → disconnect.
     *
     * <p>Normal subscription'dan farki: initial bars alindiktan sonra subscription
     * otomatik kaldirilir ve WebSocket kapatilir. Live update akisi baslatilmaz.</p>
     *
     * @param symbolFull sembol (ör. "BIST:THYAO")
     * @param period     periyot
     * @param bars       istenen bar sayisi
     * @return bar listesi iceren future
     */
    private CompletableFuture<List<CandleBar>> fetchOneShotBars(String symbolFull, String period, int bars) {
        if (!isConnected()) {
            connect();
        }

        TradingViewBarWebSocketClient client = this.wsClient;
        if (client == null) {
            return CompletableFuture.failedFuture(new RuntimeException("WebSocket bağlanamadı (one-shot)"));
        }

        CompletableFuture<List<CandleBar>> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try {
                // Bağlantı hazır olmasını bekle
                for (int i = 0; i < 50 && !client.isConnected(); i++) {
                    Thread.sleep(config.getChartWebsocketWaitMs());
                }
                if (!client.isConnected()) {
                    future.completeExceptionally(new RuntimeException("WebSocket bağlantı zaman aşımı (one-shot)"));
                    return;
                }

                // Subscription başlat
                client.subscribeToBar(symbolFull, period, bars);
                SymbolSubscription sub = barCache.getSubscription(symbolFull, period);
                if (sub == null || sub.getInitialLoadFuture() == null) {
                    future.completeExceptionally(new RuntimeException("Subscription oluşturulamadı (one-shot)"));
                    return;
                }

                // Initial bars gelince → cache'e yazılır (barCache.onInitialBars tarafından)
                // Sonra subscription kaldır + disconnect
                sub.getInitialLoadFuture().whenComplete((result, ex) -> {
                    // Diğer subscription sayısını ÖNCE kontrol et (unsubscribe cache'i değiştirir)
                    long otherSubs = barCache.getAllSubscriptions().stream()
                            .filter(s -> !(s.getSymbol().equals(symbolFull) && s.getPeriod().equals(period)))
                            .count();

                    // One-shot: veri alındı, subscription kaldır
                    if (client.isConnected()) {
                        client.unsubscribeFromBar(symbolFull, period);
                    }

                    // Başka aktif subscription yoksa disconnect
                    if (otherSubs == 0) {
                        disconnectFromTradingView();
                    }

                    if (ex != null) {
                        future.completeExceptionally(ex);
                    } else {
                        future.complete(result);
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Her dakika seans kapanis gecisini kontrol eder.
     *
     * <p>Seans acik→kapali gecisi tespit edildiginde tüm aktif topic'lere
     * {@code session_closed} mesaji gönderilir ve TradingView baglantisi kesilir.
     * Cache korunur — kapanistan sonra gelen kullanicilara cache'den servis edilir.</p>
     */
    @Scheduled(fixedDelay = 60000)
    public void checkMarketClose() {
        boolean marketNowOpen = BistTradingCalendar.isMarketOpen();

        if (marketNowOpen) {
            if (!marketWasOpen) {
                // TRANSITION: closed → open
                log.info("[CHART] Seans açıldı — alarm/watchlist/endeks quote subscribe yenileniyor");
                marketWasOpen = true;
                // WS bağlantısı gerekebilir — connect if needed
                if (!isConnected()) {
                    connect();
                }
                resubscribeAlarmQuotes();
                resubscribeWatchlistQuotes();
                subscribeIndexQuotes();
            }
            marketWasOpen = true;
            return;
        }

        // Zaten kapalıydı — transition yok
        if (!marketWasOpen) {
            return;
        }

        // TRANSITION: Market açık→kapalı
        marketWasOpen = false;
        log.info("[CHART] Seans kapandı — cleanup başlatılıyor");

        Set<String> activeTopics = barCache.getAllActiveTopics();
        if (!activeTopics.isEmpty()) {
            broadcastService.broadcastSessionClosed(activeTopics);
        }

        disconnectFromTradingView();
    }

    /**
     * TradingView WebSocket baglantisini kapatir ama cache'i korur.
     *
     * <p>Seans kapanisinda cagirilir. Bekleyen future'lar basarisiz olarak tamamlanir
     * ama cache'teki bar verileri silinmez — sonraki kullanicilar cache'den servis edilir.</p>
     */
    private void disconnectFromTradingView() {
        log.info("[CHART] TradingView disconnect (cache korunuyor)");
        barCache.failAllPendingFutures("Seans kapandı");
        disconnect();
    }

    /**
     * Servisin anlik durum bilgisini döner (debug/monitoring amacli).
     *
     * @return baglanti durumu, market durumu ve cache bilgilerini iceren map
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("connected", isConnected());
        status.put("marketOpen", BistTradingCalendar.isMarketOpen());
        status.put("cache", barCache.getStatus());
        return status;
    }

    /**
     * Uygulama kapanirken WebSocket baglantisini kapatir ve tüm cache'i temizler.
     */
    @PreDestroy
    public void cleanup() {
        log.info("[BAR-SERVICE] Cleanup başlatıldı...");
        disconnect();
        barCache.clearAll();
    }
}
