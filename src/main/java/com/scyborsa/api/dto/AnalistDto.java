package com.scyborsa.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Analist DTO sinifi.
 *
 * <p>REST API uzerinden analist verilerinin transfer edilmesinde kullanilir.
 * Entity'nin basitlestirilmis versiyonudur — audit alanlari icermez.</p>
 *
 * @see com.scyborsa.api.model.Analist
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalistDto {

    /** Analist ID'si. */
    private Long id;

    /** Analist adi. Orn: "Seyda Hoca", "Ozkan Filiz". */
    private String ad;

    /** Analist unvani. Orn: "Velzon", "Yatirim Enstitusu". */
    private String unvan;

    /** Analist resim URL'i. */
    private String resimUrl;

    /** Analistin toplam hisse onerisi sayisi. */
    private Integer hisseOnerisi;

    /** Analistin toplam kazanc tutari. */
    private Integer kazanc;

    /** Kazanc trendi. {@code true} ise yukselis, {@code false} ise dusus. */
    private Boolean trend;

    /** Chart renk kodu. Orn: "danger", "success", "warning". */
    private String chartRenk;

    /** Chart verisi — JSON array formati. */
    private String chartVerisi;

    /** Kartlarin gosterim sirasi. */
    private Integer siraNo;

    /** Analistin aktif olup olmadigini belirtir. */
    private Boolean aktif;
}
