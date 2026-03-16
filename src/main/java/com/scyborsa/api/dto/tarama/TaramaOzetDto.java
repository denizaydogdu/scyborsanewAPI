package com.scyborsa.api.dto.tarama;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tarama sonuçlarının özet istatistiklerini taşıyan DTO.
 *
 * <p>Seçilen tarih aralığındaki tüm tarama sinyallerinin toplam, ortalama,
 * başarı oranı gibi istatistiksel özetini sağlar.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaramaOzetDto {

    /** Toplam sinyal sayısı. */
    private int toplamSinyal;

    /** Gün sonu değişim ortalaması (%). */
    private Double ortalamaGetiri;

    /** Başarı oranı: pozitif gün sonu değişimli sinyal yüzdesi (%). */
    private Double basariOrani;

    /** Gün sonu değişimi pozitif olan sinyal sayısı. */
    private int pozitifSayisi;

    /** Gün sonu değişimi negatif olan sinyal sayısı. */
    private int negatifSayisi;

    /** Benzersiz hisse sayısı. */
    private int benzersizHisseSayisi;

    /** Benzersiz tarama stratejisi sayısı. */
    private int taramaSayisi;

    /** En yüksek gün sonu değişim (%). */
    private Double maxGetiri;

    /** En düşük gün sonu değişim (%). */
    private Double minGetiri;
}
