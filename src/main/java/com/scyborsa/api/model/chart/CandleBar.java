package com.scyborsa.api.model.chart;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tek bir mum (candle) çubuğu verisi.
 * <p>
 * OHLCV (Open, High, Low, Close, Volume) formatında bir periyodun
 * fiyat ve hacim bilgisini taşır. Grafik çizimi için temel veri birimidir.
 * </p>
 *
 * @see BarUpdateMessage
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CandleBar {

    /** Mumun başlangıç zamanı (epoch milisaniye). Periyodun açılış anını temsil eder. */
    private long timestamp;

    /** Periyodun açılış fiyatı (TL). */
    private double open;

    /** Periyot boyunca ulaşılan en yüksek fiyat (TL). */
    private double high;

    /** Periyot boyunca ulaşılan en düşük fiyat (TL). */
    private double low;

    /** Periyodun kapanış fiyatı (TL). Açık mumda anlık son fiyattır. */
    private double close;

    /** Periyot boyunca gerçekleşen işlem hacmi (lot). */
    private long volume;
}
