package com.scyborsa.api.dto.market;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Hazir tarama sonucunda eslesen tek bir hissenin bilgilerini tasiyan DTO.
 *
 * <p>Hisse kodu, fiyat, degisim yuzdesi, goreceli hacim ve logo bilgisi icerir.
 * {@link HazirTaramalarResponseDto} icinde liste elemani olarak kullanilir.</p>
 *
 * @see HazirTaramalarResponseDto
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HazirTaramaStockDto {

    /** Hisse borsa kodu (orn. "THYAO", "GARAN"). */
    private String stockCode;

    /** Hissenin aciklama/tam adi (orn. "Turk Hava Yollari"). */
    private String stockName;

    /** TradingView logo kimligi (orn. "turk-hava-yollari"). */
    private String logoid;

    /** Hissenin anlik fiyati (TL cinsinden). */
    private Double price;

    /** Gunluk yuzdesel degisim orani (orn. 3.25 = %3.25 artis). */
    private Double changePercent;

    /** Goreceli hacim (10 gunluk ortalamaya gore). */
    private Double relativeVolume;

    /** 10 gunluk ortalama hacim. */
    private Double avgVolume10d;

    /** 60 gunluk ortalama hacim. */
    private Double avgVolume60d;

    /** 90 gunluk ortalama hacim. */
    private Double avgVolume90d;
}
