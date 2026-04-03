package com.scyborsa.api.dto.fintables;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Finansal oranlar DTO'su.
 *
 * <p>Fintables MCP {@code hisse_finansal_tablolari_finansal_oranlari} tablosundaki
 * verileri temsil eder. Her satır bir hissenin belirli dönemdeki finansal
 * oranını (F/K, PD/DD, ROE vb.) içerir.</p>
 *
 * @see com.scyborsa.api.service.enrichment.FinansalOranService
 * @see com.scyborsa.api.service.enrichment.FinansalOranSyncJob
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinansalOranDto {

    /** Hisse senedi kodu (ör: "GARAN"). */
    private String hisseSenediKodu;

    /** Bilanço yılı. */
    private Integer yil;

    /** Bilanço ayı (3, 6, 9, 12). */
    private Integer ay;

    /** Satır numarası (sıralama). */
    private Integer satirNo;

    /** Oran kategorisi (ör: "Karlılık Oranları", "Borçluluk Oranları"). */
    private String kategori;

    /** Oran adı (ör: "F/K", "PD/DD", "ROE"). */
    private String oran;

    /** Oran değeri. */
    private Double deger;
}
