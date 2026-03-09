package com.scyborsa.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Piyasa hareket ettirici (market mover) hisse bilgisi DTO'su.
 * <p>
 * En çok yükselen veya en çok düşen hisseleri temsil eder.
 * {@link MarketMoversResponse} içinde liste elemanı olarak kullanılır.
 * </p>
 *
 * @see MarketMoversResponse
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class MarketMoverDto {

    /** Hisse borsa kodu (ör. "THYAO", "GARAN"). */
    private String ticker;

    /** Hissenin açıklama/tam adı (ör. "Türk Hava Yolları"). */
    private String description;

    /** Hissenin anlık fiyatı (TL cinsinden). */
    private double price;

    /** Günlük yüzdesel değişim oranı (ör. 3.25 = %3.25 artış). */
    private double changePercent;

    /** TradingView logo kimliği (ör. "turk-hava-yollari"). */
    private String logoid;
}
