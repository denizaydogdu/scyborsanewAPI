package com.scyborsa.api.dto.market;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Formasyon tarama sonuc DTO'su.
 *
 * <p>Django pattern screener API'sinden donen her bir formasyon tespitini temsil eder.</p>
 *
 * @see com.scyborsa.api.service.market.PatternScreenerService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatternFormationDto {

    /** Hisse kodu (orn. "THYAO"). */
    private String symbol;

    /** Formasyon adi Turkce (orn. "Yukselen Kanal"). */
    private String patternName;

    /** Formasyon guvenilirlik skoru (0-1 arasi). */
    private Double score;

    /** Hedef fiyata uzaklik yuzdesi. */
    private Double distance;

    /** Lookback pencere boyutu (10, 15, 20). */
    private Integer window;

    /** Zaman dilimi (1D veya 1W). */
    private String period;

    /** Formasyon grafik dosya adi. */
    private String filename;

    /** Hisse logosu TradingView logoid'si (orn. "thyao"). */
    private String logoid;

    /** Katilim endeksi uyesi mi? */
    @JsonProperty("katilim")
    private boolean katilim;
}
