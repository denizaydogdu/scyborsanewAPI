package com.scyborsa.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Model portföy aracı kurum DTO sınıfı.
 *
 * <p>REST API üzerinden kurum verilerinin transfer edilmesinde kullanılır.
 * Entity'nin basitleştirilmiş versiyonudur — audit alanları içermez.</p>
 *
 * @see com.scyborsa.api.model.ModelPortfoyKurum
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelPortfoyKurumDto {

    /** Kurum ID'si. */
    private Long id;

    /** Aracı kurum adı. Örn: "Burgan", "Tacirler". */
    private String kurumAdi;

    /** Kurum logo URL'i. */
    private String logoUrl;

    /** Kurum model portföyündeki hisse sayısı. */
    private Integer hisseSayisi;

    /** Kartların gösterim sırası. */
    private Integer siraNo;

    /** Kurumun aktif olup olmadigini belirtir. */
    private Boolean aktif;
}
