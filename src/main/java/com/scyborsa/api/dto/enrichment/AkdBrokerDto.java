package com.scyborsa.api.dto.enrichment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AKD tablosunda tek bir aracı kurum satırını temsil eden DTO.
 * DB'den zenginleştirilmiş isim/logo bilgisi içerir.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AkdBrokerDto {

    /** Aracı kurum kodu (ör: "ZRY"). "Diğer" satırı için "DIGER". */
    private String code;

    /** Aracı kurum tam adı (DB'den, ör: "Ziraat Yatırım Menkul Değerler"). */
    private String title;

    /** Aracı kurum kısa adı (DB'den, ör: "Ziraat"). */
    private String shortTitle;

    /** Aracı kurum logo URL'i (DB'den). */
    private String logoUrl;

    /** Lot sayısı. Satıcılar için pozitif (abs) değer gönderilir. */
    private long adet;

    /** Yüzde oranı (0-100 arası, ör: 25.03). */
    private double yuzde;

    /** Ortalama maliyet (TL). */
    private double maliyet;
}
