package com.scyborsa.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Araci kurum DTO sinifi.
 *
 * <p>REST API uzerinden araci kurum verilerinin transfer edilmesinde kullanilir.
 * Entity'nin basitlestirilmis versiyonudur — audit alanlari icermez.</p>
 *
 * @see com.scyborsa.api.model.AraciKurum
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AraciKurumDto {

    /** Araci kurum ID'si. */
    private Long id;

    /** Araci kurum kodu. Orn: "isyatirim", "garanti". */
    private String code;

    /** Araci kurum tam adi. Orn: "Is Yatirim Menkul Degerler A.S.". */
    private String title;

    /** Araci kurum kisa adi. Orn: "Is Yatirim". */
    private String shortTitle;

    /** Araci kurum logo URL'i. */
    private String logoUrl;

    /** Araci kurumun aktif olup olmadigini belirtir. */
    private Boolean aktif;

    /** Listeleme sirasi. */
    private Integer siraNo;
}
