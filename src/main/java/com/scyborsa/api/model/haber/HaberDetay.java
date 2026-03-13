package com.scyborsa.api.model.haber;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Haber detay entity — TradingView haberlerinin baslik + icerik verilerini saklar.
 *
 * <p>Headline sync ile basliklar kaydedilir, ardindan detail fetch ile icerik scrape edilir.</p>
 *
 * @see com.scyborsa.api.repository.HaberDetayRepository
 * @see com.scyborsa.api.service.HaberSyncJob
 */
@Entity
@Table(name = "haber_detay")
@Getter
@Setter
@EqualsAndHashCode(of = "id")
@ToString(exclude = {"detailContent", "shortDescription"})
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HaberDetay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** TradingView haber kimligi (unique). */
    @Column(unique = true, nullable = false)
    private String newsId;

    /** Haber basligi. */
    @Column(nullable = false, length = 1000)
    private String title;

    /** Haber saglayicisi (kap, matriks, reuters vb.). */
    private String provider;

    /** TradingView story path veya tam URL (orn. https://tr.tradingview.com/news/...). */
    @Column(length = 500)
    private String storyPath;

    /** Haber yayin zamani. */
    private LocalDateTime published;

    /** Scrape edilmis haber icerigi (HTML). */
    @Column(columnDefinition = "TEXT")
    private String detailContent;

    /** Matriks/Reuters haberleri icin kisa ozet. */
    @Column(columnDefinition = "TEXT")
    private String shortDescription;

    /** KAP haberlerindeki kap.org.tr linki. */
    @Column(length = 500)
    private String originalKapUrl;

    /** Detay scrape edildi mi? */
    @Column(nullable = false)
    private boolean fetched;

    /** Detay scrape zamani. */
    private LocalDateTime fetchedAt;

    /** Haber turu: KAP, MARKET, WORLD. */
    @Column(length = 20)
    private String newsType;

    /** Kayit olusturma zamani. */
    @Column(nullable = false)
    private LocalDateTime createTime;
}
