package com.scyborsa.api.dto.market;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Hazir tarama sonucunu tasiyan response DTO.
 *
 * <p>Secilen stratejinin adi, kategorisi ve eslesen hisse listesini icerir.
 * {@code GET /api/v1/hazir-taramalar/scan} endpoint'inin donus tipidir.</p>
 *
 * @see HazirTaramaStockDto
 * @see com.scyborsa.api.controller.HazirTaramalarController
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HazirTaramalarResponseDto {

    /** Strateji kodu (orn. "rsi_oversold"). */
    private String strategy;

    /** Stratejinin Turkce gorunen adi. */
    private String strategyDisplayName;

    /** Strateji kategorisi (MOMENTUM, TREND, HACIM, VOLATILITE, KOMPOZIT). */
    private String category;

    /** Strateji kosuluna uyan hisse listesi (degisim yuzdesine gore azalan sirali). */
    private List<HazirTaramaStockDto> stocks;

    /** Eslesen toplam hisse sayisi. */
    private int totalCount;
}
