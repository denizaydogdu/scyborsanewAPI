package com.scyborsa.api.dto.fintables;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Temel analiz sinyal DTO'su.
 *
 * <p>Bir hissenin temel analiz verilerinden türetilen sinyalleri taşır.
 * Her sinyal bağımsız bir kural tarafından üretilir (F/K düşüklüğü,
 * güçlü ROE, borç azaltma, iflas riski vb.).</p>
 *
 * <p>Sinyal yönü:</p>
 * <ul>
 *   <li>{@code "POZITIF"} — Olumlu temel analiz sinyali</li>
 *   <li>{@code "NEGATIF"} — Olumsuz temel analiz sinyali</li>
 * </ul>
 *
 * @see com.scyborsa.api.service.enrichment.TemelAnalizSinyalService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemelAnalizSinyalDto {

    /** Hisse senedi kodu (ör: "GARAN"). */
    private String hisseSenediKodu;

    /** Sinyal tipi (ör: "DUSUK_FK", "GUCLU_ROE", "IFLAS_RISKI"). */
    private String sinyalTipi;

    /** Sinyal açıklaması (ör: "F/K sektör medyanının %70'inin altında"). */
    private String sinyalAciklama;

    /** Sinyal yönü: "POZITIF" veya "NEGATIF". */
    private String sinyalYonu;

    /** Sinyalin oluşturulma tarihi. */
    private LocalDate tarih;

    /** İlgili sayısal değer (ör: F/K değeri, ROE değeri). */
    private Double deger;
}
