package com.scyborsa.api.constants;

/**
 * TradingView entegrasyonunda kullanilan sabit degerler.
 *
 * <p>WebSocket baglanti URL'i, timeout/heartbeat sureleri ve borsa kodu gibi
 * degismeyen parametreleri icerir.</p>
 *
 * <p>Bu sinif instantiate edilemez.</p>
 *
 * @see com.scyborsa.api.config.TradingViewConfig
 */
public final class TradingViewConstant {

    /** Olusturulamaz sabit sinifi. */
    private TradingViewConstant() {
        throw new UnsupportedOperationException("Constant class cannot be instantiated");
    }

    /** TradingView canli veri WebSocket URL'i. */
    public static final String WEBSOCKET_URL = "wss://data.tradingview.com/socket.io/websocket";

    /** WebSocket baglanti zaman asimi suresi (saniye). */
    public static final int WEBSOCKET_TIMEOUT_SECONDS = 60;

    /** WebSocket heartbeat (ping) gonderim araligi (saniye). */
    public static final int WEBSOCKET_HEARTBEAT_SECONDS = 30;

    /** Borsa Istanbul (BIST) borsa kodu. */
    public static final String EXCHANGE_BIST = "BIST";
}
