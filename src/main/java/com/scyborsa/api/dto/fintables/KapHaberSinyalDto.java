package com.scyborsa.api.dto.fintables;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * KAP haber sinyal DTO'su.
 *
 * <p>Son 30 günün KAP haberlerinden türetilen sinyalleri taşır. Keyword bazlı
 * sınıflandırma ile haber tipi belirlenir.</p>
 *
 * <p>Haber tipleri:</p>
 * <ul>
 *   <li>{@code SERMAYE_ARTIRIMI} — Bedelli/bedelsiz sermaye artırımı</li>
 *   <li>{@code TEMETTU} — Temettü / kâr payı dağıtımı</li>
 *   <li>{@code GUIDANCE} — Şirket yönlendirmesi</li>
 *   <li>{@code GERI_ALIM} — Pay geri alım programı</li>
 *   <li>{@code ORTAKLIK_DEGISIKLIGI} — Ortaklık yapısı değişikliği</li>
 *   <li>{@code VBTS} — VBTS tedbiri</li>
 * </ul>
 *
 * @see com.scyborsa.api.service.enrichment.KapHaberSinyalService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KapHaberSinyalDto {

    /** Hisse senedi kodu (ör: "GARAN"). */
    private String hisseSenediKodu;

    /** Haber tipi: SERMAYE_ARTIRIMI, TEMETTU, GUIDANCE, GERI_ALIM, ORTAKLIK_DEGISIKLIGI, VBTS. */
    private String haberTipi;

    /** Haber özeti. */
    private String haberOzet;

    /** Sinyal yönü: "POZITIF" veya "NEGATIF". */
    private String sinyalYonu;

    /** Haberin tarihi. */
    private LocalDate tarih;
}
