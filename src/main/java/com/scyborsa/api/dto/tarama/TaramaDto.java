package com.scyborsa.api.dto.tarama;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tekil tarama sinyali bilgilerini taşıyan DTO.
 *
 * <p>Her bir tarama sonucunu (hisse, fiyat, değişim, tarama adı vb.) temsil eder.
 * Aynı hisse+gün+zaman kombinasyonunda birden fazla tarama varsa {@code grouped=true}
 * olarak işaretlenir ve {@code screenerName} "N Tarama" formatında gösterilir.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaramaDto {

    /** Tarama sonucu birincil anahtarı. */
    private Long id;

    /** Hisse borsa kodu (ör. "THYAO"). */
    private String stockName;

    /** Tarama anındaki hisse fiyatı. */
    private Double price;

    /** Değişim yüzdesi (ör. 3.5 = %3.5 artış). */
    private Double percentage;

    /** Tarama stratejisi adı (ör. "BIST_VEGAS") veya gruplu ise "N Tarama". */
    private String screenerName;

    /** Tarama türü (enum string değeri). */
    private String screenerType;

    /** Tarama zamanı (HH:mm formatında). */
    private String screenerTime;

    /** Tarama günü (yyyy-MM-dd formatında). */
    private String screenerDay;

    /** Gün sonu değişim yüzdesi: (kapanış - giriş) / giriş × 100. */
    private Double gunSonuDegisim;

    /** Aynı hisse+gün+zaman'da birden fazla tarama varsa {@code true}. */
    private boolean grouped;
}
