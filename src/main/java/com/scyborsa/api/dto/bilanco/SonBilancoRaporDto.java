package com.scyborsa.api.dto.bilanco;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Son bilanco raporu metadata DTO'su.
 *
 * <p>Bir hissenin KAP'a bildirilen en son finansal rapor bilgilerini icerir.
 * Raporun kendisini degil, raporun meta verilerini tasir (sembol, yil, donem, vb.).</p>
 *
 * <p>Donem (period) degerleri:</p>
 * <ul>
 *   <li>1 = 3 Aylik (Q1)</li>
 *   <li>2 = 6 Aylik (Q2)</li>
 *   <li>3 = 9 Aylik (Q3)</li>
 *   <li>4 = Yillik (Q4)</li>
 * </ul>
 *
 * <p>Konsolidasyon (consolidation) degerleri:</p>
 * <ul>
 *   <li>CS = Konsolide</li>
 *   <li>NC = Konsolide Olmayan</li>
 * </ul>
 *
 * @see com.scyborsa.api.service.bilanco.BilancoService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SonBilancoRaporDto {

    /** Hisse sembolü (orn. GARAN, THYAO). */
    private String symbol;

    /** Rapor yili. */
    private Integer year;

    /** Rapor donemi (1=3 ay, 2=6 ay, 3=9 ay, 4=yillik). */
    private Integer period;

    /** Bildirim nedeni (NEW=yeni, CORR=duzeltme). */
    private String disclosureReason;

    /** KAP bildirim zamani (yyyy-MM-ddTHH:mm:ss). */
    private String time;

    /** KAP bildirim sayfasi linki. */
    private String link;

    /** Konsolidasyon durumu (CS=konsolide, NC=konsolide olmayan). */
    private String consolidation;

    /** KAP bildirim indeks numarasi. */
    private Integer disclosureIndex;

    /** Katilim endeksi uyesi mi? */
    @JsonProperty("katilim")
    private boolean katilim;
}
