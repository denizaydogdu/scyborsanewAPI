package com.scyborsa.api.dto.fintables;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Finansal tablo DTO'su (bilanço, gelir tablosu, nakit akım).
 *
 * <p>Fintables MCP'deki {@code hisse_finansal_tablolari_bilanco_kalemleri},
 * {@code hisse_finansal_tablolari_gelir_tablosu_kalemleri} ve
 * {@code hisse_finansal_tablolari_nakit_akis_tablosu_kalemleri} tablolarındaki
 * verileri temsil eder.</p>
 *
 * <p>{@code tabloTipi} alanı verinin hangi tablodan geldiğini belirler:
 * BILANCO, GELIR veya NAKIT_AKIM.</p>
 *
 * @see com.scyborsa.api.service.enrichment.FinansalTabloService
 * @see com.scyborsa.api.service.enrichment.FinansalTabloSyncJob
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinansalTabloDto {

    /** Hisse senedi kodu (ör: "GARAN"). */
    private String hisseSenediKodu;

    /** Finansal tablo yılı. */
    private Integer yil;

    /** Finansal tablo ayı (3, 6, 9, 12). */
    private Integer ay;

    /** Satır numarası (kalem sırası). */
    private Integer satirNo;

    /** Finansal kalem adı (ör: "Dönen Varlıklar", "Hasılat"). */
    private String kalem;

    /** TRY dönemsel değer. */
    private Long tryDonemsel;

    /** USD dönemsel değer. */
    private Long usdDonemsel;

    /** EUR dönemsel değer. */
    private Long eurDonemsel;

    /** TRY çeyreklik değer (sadece gelir tablosu ve nakit akım). */
    private Long tryCeyreklik;

    /** USD çeyreklik değer (sadece gelir tablosu ve nakit akım). */
    private Long usdCeyreklik;

    /** EUR çeyreklik değer (sadece gelir tablosu ve nakit akım). */
    private Long eurCeyreklik;

    /** TRY TTM (son 12 ay) değer (sadece gelir tablosu ve nakit akım). */
    private Long tryTtm;

    /** USD TTM (son 12 ay) değer (sadece gelir tablosu ve nakit akım). */
    private Long usdTtm;

    /** EUR TTM (son 12 ay) değer (sadece gelir tablosu ve nakit akım). */
    private Long eurTtm;

    /** Tablo tipi: BILANCO, GELIR veya NAKIT_AKIM. */
    private String tabloTipi;
}
