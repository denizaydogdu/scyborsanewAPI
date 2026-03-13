package com.scyborsa.api.dto.kap;

import lombok.*;

/**
 * Haber detay veri transfer nesnesi.
 *
 * <p>{@link com.scyborsa.api.model.haber.HaberDetay} entity'sinin API katmaninda
 * kullanilan DTO karsiligi. Yayin zamani formatlanmis string olarak tasir.</p>
 *
 * @see com.scyborsa.api.model.haber.HaberDetay
 * @see com.scyborsa.api.controller.KapHaberController
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HaberDetayDto {

    /** Veritabani ID'si. */
    private Long id;

    /** TradingView haber kimligi. */
    private String newsId;

    /** Haber basligi. */
    private String title;

    /** Haber saglayicisi (kap, matriks, reuters vb.). */
    private String provider;

    /** Scrape edilmis haber icerigi (HTML). */
    private String detailContent;

    /** Kisa ozet (piyasa/dunya haberleri icin). */
    private String shortDescription;

    /** KAP haberlerindeki kap.org.tr linki. */
    private String originalKapUrl;

    /** Formatlanmis yayin zamani (orn. "03 Mar 2026 14:30"). */
    private String publishedFormatted;

    /** Haber turu: KAP, MARKET, WORLD. */
    private String newsType;

    /** Detay scrape edildi mi? */
    private boolean fetched;
}
