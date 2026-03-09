package com.scyborsa.api.dto.fund;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * TEFAS fon zaman serisi veri noktasi DTO sinifi.
 *
 * <p>Nakit akisi (cashflow) ve yatirimci sayisi (investors) gibi
 * tarih-deger ciftlerinden olusan zaman serisi verilerini tasir.</p>
 *
 * <p>api.velzon.tr'deki {@code /api/funds/{code}/cashflow} ve
 * {@code /api/funds/{code}/investors} endpoint'lerinden donen
 * veri formatiyla eslesir.</p>
 *
 * @see FundDetailDto
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FundTimeSeriesDto {

    /** Tarih (orn. "2026-03-05"). */
    private String date;

    /** Deger (nakit akisi TL veya yatirimci sayisi). */
    private Double value;
}
