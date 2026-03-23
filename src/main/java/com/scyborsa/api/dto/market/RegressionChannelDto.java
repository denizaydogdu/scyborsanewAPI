package com.scyborsa.api.dto.market;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Regresyon kanali tarama sonuc DTO'su.
 *
 * <p>Django indicator screener API'sinden donen her bir hissenin
 * regresyon kanali verilerini temsil eder.</p>
 *
 * @see com.scyborsa.api.service.market.RegressionScreenerService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegressionChannelDto {

    /** Hisse kodu (orn. "THYAO"). */
    private String symbol;

    /** Regresyon periyodu (bar sayisi). */
    private Integer period;

    /** R-kare degeri (0-1 arasi, trend gucu gostergesi). */
    private Double r2;

    /** Egim yonu (orn. "up", "down"). */
    private String slope;

    /** Kanal pozisyonu (orn. "above", "upper", "middle", "lower", "below"). */
    private String position;

    /** Kanal ici yuzde pozisyonu (0-100 arasi). */
    private Double pctPosition;

    /** Hisse logosu TradingView logoid'si (orn. "thyao"). */
    private String logoid;
}
