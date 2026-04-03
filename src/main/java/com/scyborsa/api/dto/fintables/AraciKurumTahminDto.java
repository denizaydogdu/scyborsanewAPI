package com.scyborsa.api.dto.fintables;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Aracı kurum tahmin DTO'su.
 *
 * <p>Fintables MCP {@code hisse_senedi_araci_kurum_tahminleri} tablosundaki
 * verileri temsil eder. Her satır bir aracı kurumun belirli bir hisse için
 * yıllık/aylık satış, FAVÖK ve net kâr tahminini içerir.</p>
 *
 * @see com.scyborsa.api.service.AnalystRatingService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AraciKurumTahminDto {

    /** Hisse senedi kodu (ör: "GARAN"). */
    private String hisseSenediKodu;

    /** Aracı kurum kodu (ör: "AKY"). */
    private String araciKurumKodu;

    /** Tahmin yılı. */
    private Integer yil;

    /** Tahmin ayı. */
    private Integer ay;

    /** Satış tahmini (TRY). */
    private Double satislar;

    /** FAVÖK tahmini (TRY). */
    private Double favok;

    /** Net kâr tahmini (TRY). */
    private Double netKar;
}
