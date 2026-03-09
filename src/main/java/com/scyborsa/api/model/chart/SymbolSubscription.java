package com.scyborsa.api.model.chart;

import lombok.Data;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bir sembol/periyot çifti için aktif WebSocket abonelik durumu.
 * <p>
 * TradingView chart session'ı ile client arasındaki canlı veri akışını yönetir.
 * Thread-safe yapıdadır; {@code volatile} field'lar ve {@link CopyOnWriteArrayList}
 * kullanılarak eşzamanlı erişim güvenliği sağlanır.
 * </p>
 *
 * @see CandleBar
 * @see BarUpdateMessage
 */
@Data
public class SymbolSubscription {

    /** Abone olunan hisse/sembol kodu (ör. "THYAO"). */
    private String symbol;

    /** Mum periyodu (dakika cinsinden, ör. "30"). */
    private String period;

    /** TradingView chart session kimliği. Veri akışı bu session üzerinden yapılır. */
    private volatile String chartSessionId;

    /** Client'ın talep ettiği başlangıç mum sayısı. */
    private int requestedBars;

    /** Bellekte tutulan mum verileri. Thread-safe liste yapısı kullanılır. */
    private List<CandleBar> bars = new CopyOnWriteArrayList<>();

    /** Son güncelleme zamanı (epoch milisaniye). Stale bağlantı tespiti için kullanılır. */
    private volatile long lastUpdateTime;

    /** Canlı veri akışının aktif olup olmadığını belirtir. */
    private volatile boolean streaming;

    /** İlk toplu mum verisinin yüklenip yüklenmediğini belirtir. */
    private volatile boolean initialLoadDone;

    /** İlk yükleme tamamlanana kadar bekleyen future. Serialization dışında tutulur (transient). */
    private transient volatile CompletableFuture<List<CandleBar>> initialLoadFuture;

    /**
     * Bu abonelik için benzersiz cache anahtarı oluşturur.
     *
     * @return "SEMBOL:PERIYOT" formatında cache anahtarı (ör. "THYAO:30")
     */
    public String getCacheKey() {
        return symbol + ":" + period;
    }
}
