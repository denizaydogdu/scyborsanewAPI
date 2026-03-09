package com.scyborsa.api.model.chart;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Grafik mum (candle bar) güncelleme mesajı.
 * <p>
 * WebSocket {@code /topic/chart/{symbol}} kanalı üzerinden client'a gönderilir.
 * İlk yükleme, yeni mum oluşumu ve mevcut mum güncellemesi olmak üzere
 * üç farklı mesaj tipi taşıyabilir.
 * </p>
 *
 * @see CandleBar
 * @see com.scyborsa.api.dto.ChartSubscribeRequest
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BarUpdateMessage {

    /** Güncellemenin ait olduğu hisse/sembol kodu (ör. "THYAO"). */
    private String symbol;

    /** Mum periyodu (dakika cinsinden, ör. "30"). */
    private String period;

    /**
     * Mesaj tipi:
     * <ul>
     *   <li>{@code "initial"} - İlk abonelikte geçmiş mum verisi toplu gönderimi</li>
     *   <li>{@code "new_bar"} - Yeni bir mum periyodunun başlaması</li>
     *   <li>{@code "update"} - Mevcut açık mumun güncellenmesi</li>
     * </ul>
     */
    private String type;

    /** Gönderilen mum verileri listesi. "initial" tipinde tüm geçmiş mumları, diğerlerinde tek mum içerir. */
    private List<CandleBar> bars;

    /** Sunucuda bu sembol/periyot için tutulan toplam mum sayısı. */
    private int totalBars;

    /** Sunucu zamanı (epoch milisaniye). Client-server zaman senkronizasyonu için kullanılır. */
    private long serverTime;
}
