package com.scyborsa.api.dto.fintables;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Portföy vs BIST100 overlay karşılaştırma DTO'su.
 *
 * <p>Kullanıcının portföyündeki hisselerin ortalama temel analiz değerlerini
 * BIST100 ortalaması ile karşılaştırır. Genel değerlendirme
 * "GÜÇLÜ", "ORTA" veya "ZAYIF" olarak döner.</p>
 *
 * @see com.scyborsa.api.service.enrichment.PortfoyOverlayService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortfoyOverlayDto {

    /** Portföydeki hisselerin ortalama F/K oranı. */
    private Double portfoyOrtalamaFk;

    /** BIST100 hisselerinin ortalama F/K oranı. */
    private Double bist100OrtalamaFk;

    /** Portföydeki hisselerin ortalama ROE değeri (%). */
    private Double portfoyOrtalamaRoe;

    /** BIST100 hisselerinin ortalama ROE değeri (%). */
    private Double bist100OrtalamaRoe;

    /** Portföydeki pozitif sinyal sayısı toplamı. */
    private Integer portfoyPozitifSinyalSayisi;

    /** Portföydeki negatif sinyal sayısı toplamı. */
    private Integer portfoyNegatifSinyalSayisi;

    /** Genel değerlendirme: "GÜÇLÜ", "ORTA" veya "ZAYIF". */
    private String genelDegerlendirme;
}
