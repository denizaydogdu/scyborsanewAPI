package com.scyborsa.api.dto.market;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Hazir tarama stratejisi tanim bilgilerini tasiyan DTO.
 *
 * <p>Strateji kodu, gorunen adi, aciklamasi ve kategorisini icerir.
 * {@code GET /api/v1/hazir-taramalar/strategies} endpoint'inin donus tipidir.</p>
 *
 * @see com.scyborsa.api.enums.PresetStrategyEnum
 * @see com.scyborsa.api.controller.HazirTaramalarController
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PresetStrategyDto {

    /** Strateji kodu (orn. "rsi_oversold"). */
    private String code;

    /** Stratejinin Turkce gorunen adi. */
    private String displayName;

    /** Strateji aciklamasi. */
    private String description;

    /** Strateji kategorisi (MOMENTUM, TREND, HACIM, VOLATILITE, KOMPOZIT). */
    private String category;
}
