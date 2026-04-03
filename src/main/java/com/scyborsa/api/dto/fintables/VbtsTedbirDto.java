package com.scyborsa.api.dto.fintables;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * VBTS tedbirli hisse DTO'su.
 *
 * <p>Fintables MCP {@code hisse_vbts_tedbirleri} tablosundaki verileri temsil eder.
 * Her satır bir hissenin aktif VBTS tedbirini içerir (kredili alım, brut takas,
 * tek fiyat, emir paketi, internet emir yasağı, emir iptal yasağı).</p>
 *
 * @see com.scyborsa.api.service.enrichment.VbtsTedbirSyncJob
 * @see com.scyborsa.api.service.enrichment.VbtsTedbirService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VbtsTedbirDto {

    /** Tedbir kayıt ID'si. */
    private Integer tedbirId;

    /** Hisse senedi kodu (ör: "GARAN"). */
    private String hisseSenediKodu;

    /** Tedbir tipi (kredili_alim_aciga_satis, brut_takas, tek_fiyat, emir_paketi, internet_emir_yasağı, emir_iptal_yasağı). */
    private String tedbirTipi;

    /** Tedbir başlangıç tarihi (yyyy-MM-dd formatı). */
    private String tedbirBaslangicTarihi;

    /** Tedbir bitiş tarihi (yyyy-MM-dd formatı, null ise devam ediyor). */
    private String tedbirBitisTarihi;

    /** KAP bildirim ID'si. */
    private Long kapBildirimId;
}
