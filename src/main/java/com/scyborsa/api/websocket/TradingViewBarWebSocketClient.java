package com.scyborsa.api.websocket;

import com.scyborsa.api.config.TradingViewConfig;
import com.scyborsa.api.model.chart.CandleBar;
import com.scyborsa.api.model.chart.SymbolSubscription;
import com.scyborsa.api.service.chart.BarBroadcastService;
import com.scyborsa.api.service.chart.BarCache;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * TradingView WebSocket istemcisi.
 * TradingView'in gercek zamanli veri akisi protokolu uzerinden bar (mum) ve
 * fiyat teklifi (quote) verilerini alir. Alinan veriler {@link BarCache}'e kaydedilir
 * ve {@link BarBroadcastService} uzerinden istemcilere yayinlanir.
 *
 * <p>Desteklenen islemler:</p>
 * <ul>
 *   <li>Bar (mum grafigi) aboneligi ve gercek zamanli guncelleme</li>
 *   <li>Quote (anlik fiyat) aboneligi</li>
 *   <li>Heartbeat yanitlama</li>
 *   <li>Otomatik yeniden baglanti destegiyle hata yonetimi</li>
 * </ul>
 */
@Slf4j
public class TradingViewBarWebSocketClient extends WebSocketClient {

    private final TradingViewConfig config;
    private final BarCache barCache;
    private final BarBroadcastService broadcastService;
    private volatile boolean connected = false;
    private volatile String quoteSessionId;
    private final Set<String> quotedSymbols = ConcurrentHashMap.newKeySet();

    /**
     * TradingView WebSocket istemcisini olusturur.
     *
     * @param serverUri        TradingView WebSocket sunucu URI'si
     * @param httpHeaders      HTTP header'lari (Origin, User-Agent vb.)
     * @param config           TradingView konfigurasyonu (auth token vb.)
     * @param barCache         bar verilerinin tutuldugu cache
     * @param broadcastService bar ve quote guncellemelerinin yayinlandigi servis
     */
    public TradingViewBarWebSocketClient(URI serverUri, Map<String, String> httpHeaders,
                                          TradingViewConfig config, BarCache barCache,
                                          BarBroadcastService broadcastService) {
        super(serverUri, httpHeaders);
        this.config = config;
        this.barCache = barCache;
        this.broadcastService = broadcastService;
    }

    /**
     * WebSocket baglantisi acildiginda cagirilir.
     * Quote session olusturur, auth token gondererek kimlik dogrulama yapar
     * ve gerekli alanlari (fiyat, hacim vb.) set eder.
     *
     * @param handshake sunucu el sikisma bilgisi
     */
    @Override
    public void onOpen(ServerHandshake handshake) {
        log.info("[BAR-WS] WebSocket bağlandı! HTTP Status: {}", handshake.getHttpStatus());
        connected = true;

        quoteSessionId = "qs_" + generateId();

        String authToken = config.getWebsocketAuthToken() != null ? config.getWebsocketAuthToken() : "";

        sendTvMessage("{\"m\":\"set_data_quality\",\"p\":[\"low\"]}");
        sendTvMessage("{\"m\":\"set_auth_token\",\"p\":[\"" + authToken + "\"]}");
        sendTvMessage("{\"m\":\"set_locale\",\"p\":[\"tr\",\"TR\"]}");
        sendTvMessage("{\"m\":\"quote_create_session\",\"p\":[\"" + quoteSessionId + "\"]}");
        sendTvMessage("{\"m\":\"quote_set_fields\",\"p\":[\"" + quoteSessionId + "\","
                + "\"ch\",\"chp\",\"lp\",\"lp_time\",\"volume\",\"short_name\",\"description\","
                + "\"exchange\",\"high_price\",\"low_price\",\"open_price\",\"prev_close_price\"]}");

        log.info("[BAR-WS] Auth flow tamamlandı, quoteSession={}", quoteSessionId);
    }

    /**
     * TradingView'den mesaj alindiginda cagirilir.
     * Ham mesaj frame'lere ayrilir ve her frame ayri ayri islenir.
     *
     * @param message TradingView protokolune uygun ham mesaj
     */
    @Override
    public void onMessage(String message) {
        try {
            List<String> frames = TvMessageParser.splitFrames(message);
            for (String frame : frames) {
                processFrame(frame);
            }
        } catch (Exception e) {
            log.error("[BAR-WS] Mesaj işleme hatası: {}", e.getMessage(), e);
        }
    }

    private void processFrame(String frame) {
        if (TvMessageParser.isHeartbeat(frame)) {
            sendTvMessage(frame);
            return;
        }

        String type = TvMessageParser.getMessageType(frame);
        if (type == null) return;

        switch (type) {
            case "timescale_update":
                handleTimescaleUpdate(frame);
                break;
            case "du":
                handleDataUpdate(frame);
                break;
            case "symbol_resolved":
                String symbol = TvMessageParser.parseSymbolFromResolved(frame);
                log.info("[BAR-WS] Symbol resolved: {}", symbol);
                break;
            case "series_completed":
                log.debug("[BAR-WS] Series completed");
                break;
            case "qsd":
                handleQsd(frame);
                break;
            case "critical_error":
            case "protocol_error":
                log.error("[BAR-WS] HATA mesajı: {}", frame);
                break;
            default:
                log.debug("[BAR-WS] Bilinmeyen mesaj tipi: {}", type);
                break;
        }
    }

    private void handleQsd(String frame) {
        Map<String, Object> quoteData = TvMessageParser.parseQsdData(frame);
        if (quoteData != null && quoteData.get("lp") != null) {
            broadcastService.broadcastQuoteUpdate(quoteData);
        }
    }

    private void handleTimescaleUpdate(String frame) {
        String chartSessionId = TvMessageParser.getChartSessionId(frame);
        List<CandleBar> bars = TvMessageParser.parseBarsFromMessage(frame);

        if (chartSessionId == null || bars.isEmpty()) {
            return;
        }

        var sub = barCache.getBySessionId(chartSessionId);
        if (sub == null) {
            log.warn("[BAR-WS] timescale_update: subscription bulunamadı: {}", chartSessionId);
            return;
        }

        if (sub.isInitialLoadDone()) {
            String updateType = determineUpdateType(sub, bars);
            barCache.onBarUpdate(chartSessionId, bars);
            broadcastService.broadcastBarUpdate(sub, bars, updateType);
        } else {
            barCache.onInitialBars(chartSessionId, bars);
            broadcastService.broadcastInitialLoad(sub);
        }
    }

    private void handleDataUpdate(String frame) {
        String chartSessionId = TvMessageParser.getChartSessionId(frame);
        List<CandleBar> updatedBars = TvMessageParser.parseBarsFromMessage(frame);

        if (chartSessionId == null || updatedBars.isEmpty()) return;

        var sub = barCache.getBySessionId(chartSessionId);
        if (sub == null) return;

        String updateType = determineUpdateType(sub, updatedBars);
        barCache.onBarUpdate(chartSessionId, updatedBars);
        broadcastService.broadcastBarUpdate(sub, updatedBars, updateType);
    }

    private String determineUpdateType(SymbolSubscription sub, List<CandleBar> updatedBars) {
        if (updatedBars.isEmpty() || sub.getBars().isEmpty()) return "new_bar";
        CandleBar lastCached = sub.getBars().get(sub.getBars().size() - 1);
        CandleBar firstUpdate = updatedBars.get(0);
        return lastCached.getTimestamp() == firstUpdate.getTimestamp() ? "update" : "new_bar";
    }

    /**
     * Belirli bir sembol ve periyot icin bar (mum grafigi) aboneligi baslatir.
     * Ayni zamanda quote aboneligi de olusturur (eger daha once eklenmemisse).
     *
     * @param symbol hisse sembolu (ornegin "THYAO" veya "BIST:THYAO")
     * @param period zaman periyodu (ornegin "30", "1D", "1W")
     * @param bars   istenen bar sayisi
     */
    public void subscribeToBar(String symbol, String period, int bars) {
        String chartSessionId = "cs_" + generateId();
        String symbolFull = symbol.contains(":") ? symbol : "BIST:" + symbol;

        barCache.createSubscription(symbolFull, period, chartSessionId, bars);

        sendTvMessage("{\"m\":\"chart_create_session\",\"p\":[\"" + chartSessionId + "\",\"disable_statistic\"]}");
        sendTvMessage("{\"m\":\"switch_timezone\",\"p\":[\"" + chartSessionId + "\",\"Europe/Istanbul\"]}");

        String symbolJson = "={\\\"symbol\\\":\\\"" + symbolFull + "\\\","
                + "\\\"adjustment\\\":\\\"splits\\\","
                + "\\\"session\\\":\\\"regular\\\"}";
        String resolveMsg = "{\"m\":\"resolve_symbol\",\"p\":[\"" + chartSessionId + "\","
                + "\"symbol_1\","
                + "\"" + symbolJson + "\"]}";
        sendTvMessage(resolveMsg);

        String tvPeriod = convertPeriod(period);
        sendTvMessage("{\"m\":\"create_series\",\"p\":[\"" + chartSessionId + "\","
                + "\"s1\",\"s1\","
                + "\"symbol_1\","
                + "\"" + tvPeriod + "\","
                + bars + ",\"\"]}");

        if (quotedSymbols.add(symbolFull)) {
            sendTvMessage("{\"m\":\"quote_add_symbols\",\"p\":[\"" + quoteSessionId + "\",\"" + symbolFull + "\"]}");
            sendTvMessage("{\"m\":\"quote_fast_symbols\",\"p\":[\"" + quoteSessionId + "\",\"" + symbolFull + "\"]}");
        }

        log.info("[BAR-WS] Bar subscription başlatıldı: {} period={} bars={} chartSession={}",
                symbolFull, period, bars, chartSessionId);
    }

    /**
     * Belirli bir sembol icin sadece quote (anlik fiyat) aboneligi olusturur.
     * Bar aboneligi olmadan sadece fiyat guncellemeleri alinir.
     *
     * @param symbol hisse sembolu (ornegin "THYAO" veya "BIST:THYAO")
     */
    public void subscribeQuoteOnly(String symbol) {
        String symbolFull = symbol.contains(":") ? symbol : "BIST:" + symbol;
        if (quotedSymbols.add(symbolFull)) {
            sendTvMessage("{\"m\":\"quote_add_symbols\",\"p\":[\"" + quoteSessionId + "\",\"" + symbolFull + "\"]}");
            sendTvMessage("{\"m\":\"quote_fast_symbols\",\"p\":[\"" + quoteSessionId + "\",\"" + symbolFull + "\"]}");
            log.info("[BAR-WS] Quote-only subscription eklendi: {}", symbolFull);
        }
    }

    /**
     * Belirli bir sembol ve periyot icin bar aboneligini iptal eder.
     * Chart session'i kapatir ve cache'den aboneligi siler.
     *
     * @param symbol hisse sembolu (ornegin "THYAO" veya "BIST:THYAO")
     * @param period zaman periyodu (ornegin "30", "1D")
     */
    public void unsubscribeFromBar(String symbol, String period) {
        String symbolFull = symbol.contains(":") ? symbol : "BIST:" + symbol;
        var sub = barCache.getSubscription(symbolFull, period);
        if (sub == null) return;

        String chartSessionId = sub.getChartSessionId();
        sendTvMessage("{\"m\":\"remove_series\",\"p\":[\"" + chartSessionId + "\",\"s1\"]}");
        sendTvMessage("{\"m\":\"chart_delete_session\",\"p\":[\"" + chartSessionId + "\"]}");
        barCache.removeSubscription(symbolFull, period);
        log.info("[BAR-WS] Bar unsubscribe: {} period={}", symbolFull, period);
    }

    /**
     * WebSocket baglantisi kapatildiginda cagirilir.
     * Baglanti durumunu gunceller ve bekleyen tum future'lari hata ile tamamlar.
     *
     * @param code   kapanis kodu
     * @param reason kapanis nedeni
     * @param remote uzak taraftan mi kapatildi
     */
    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.warn("[BAR-WS] WebSocket kapandı! Code: {}, Reason: {}, Remote: {}", code, reason, remote);
        connected = false;
        barCache.failAllPendingFutures("WebSocket bağlantısı kapandı (code: " + code + ")");
    }

    /**
     * WebSocket hatasi olustiginda cagirilir.
     * Baglanti durumunu gunceller ve bekleyen tum future'lari hata ile tamamlar.
     *
     * @param ex olusan hata
     */
    @Override
    public void onError(Exception ex) {
        log.error("[BAR-WS] WebSocket hatası: {}", ex.getMessage());
        connected = false;
        barCache.failAllPendingFutures("WebSocket hatası: " + ex.getMessage());
    }

    /**
     * WebSocket baglantisinin aktif ve acik olup olmadigini kontrol eder.
     *
     * @return baglanti aktif ve aciksa {@code true}
     */
    public boolean isConnected() {
        return connected && isOpen();
    }

    private void sendTvMessage(String json) {
        if (isOpen()) {
            send(TvMessageParser.wrapMessage(json));
        } else {
            log.warn("[BAR-WS] WS açık değil, mesaj gönderilemedi");
        }
    }

    private String generateId() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(chars.charAt(ThreadLocalRandom.current().nextInt(chars.length())));
        }
        return sb.toString();
    }

    private String convertPeriod(String period) {
        if (period == null) return "30";
        switch (period.toUpperCase()) {
            case "D": case "1D": case "DAILY": case "GUNLUK": return "1D";
            case "W": case "1W": case "WEEKLY": case "HAFTALIK": return "1W";
            default: return period;
        }
    }
}
