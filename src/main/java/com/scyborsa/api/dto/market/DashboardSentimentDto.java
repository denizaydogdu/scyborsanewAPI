package com.scyborsa.api.dto.market;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Dashboard piyasa sentiment (duyarlilik) verisini tasiyan DTO.
 *
 * <p>BIST hisselerinin kisa, orta ve uzun vadeli yukselis oranlarini
 * yuzde olarak icerir. Velzon API'den alinan performans verisi
 * uzerinden hesaplanir.</p>
 *
 * @see com.scyborsa.api.service.market.SentimentService
 * @see com.scyborsa.api.controller.DashboardController
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DashboardSentimentDto {

    /** Kisa vadeli yukselis orani (%). */
    private double kisaVadeli;

    /** Orta vadeli yukselis orani (%). */
    private double ortaVadeli;

    /** Uzun vadeli yukselis orani (%). */
    private double uzunVadeli;

    /** Toplam hisse sayisi. */
    private int toplamHisse;

    /** Veri zamani (ISO 8601 formati). */
    private String timestamp;
}
