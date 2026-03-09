package com.scyborsa.api.dto.enrichment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Kurumsal Guc Skoru (KGS) veri transfer nesnesi.
 *
 * <p>KGS 2.0 sistemi: 8 bilesenden olusan 0-100 skor.
 * Telegram mesajlarinda hisse gucunu gostermek icin kullanilir.</p>
 *
 * @see com.scyborsa.api.service.enrichment.KurumsalGucService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KurumsalGucDTO {

    /** Genel KGS skoru (0-100). null = veri yok. */
    private Integer skor;

    /** Skor emojisi (🟢🟢, 🟢, ⚪, 🟡, 🔴). */
    private String emoji;

    /** Skor etiketi (Guclu Birikim, Birikim, Notr, Zayif, Dagitim). */
    private String etiket;

    /** Momentum alt skoru (0-100). null = veri yok. */
    private Integer momentumSkoru;

    /** 30 gunluk net pozisyon (TL). */
    private String formattedNetPozisyonTL;

    /** Sureklilik (orn. "24/30"). */
    private String formattedSureklilik;

    /** Top 5 kurum yogunlasmasi (orn. "45.67%"). */
    private String formattedYogunlasma;

    /** Alici kurum sayisi. */
    private Integer aliciKurumSayisi;

    /** Guven skoru (0-100). */
    private Integer confidenceScore;

    /** Virman tespit edildi mi? */
    private boolean virmanDetected;

    /** Virman ciddiyet seviyesi (HIGH, MEDIUM, LOW). */
    private String virmanSeverity;

    /** Virman turu (SUDDEN_ZERO, REVERSE_FLOW, VOLUME_SPIKE, RECONCILIATION_MISMATCH). */
    private String virmanType;
}
