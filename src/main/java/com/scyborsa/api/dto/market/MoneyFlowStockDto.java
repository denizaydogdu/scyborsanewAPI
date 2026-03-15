package com.scyborsa.api.dto.market;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Para akisi hisse bilgisi DTO'su.
 * <p>
 * Islem hacmine gore en yuksek ciro yapan hisseleri temsil eder.
 * Pozitif degisim → para girisi, negatif degisim → para cikisi olarak siniflandirilir.
 * </p>
 *
 * @see MoneyFlowResponse
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoneyFlowStockDto {

    /** Hisse borsa kodu (orn. "THYAO", "GARAN"). */
    private String ticker;

    /** Hissenin aciklama/tam adi (orn. "Turk Hava Yollari"). */
    private String description;

    /** TradingView logo kimlik degeri (CDN proxy icin). */
    private String logoid;

    /** Hissenin anlik fiyati (TL cinsinden). */
    private double price;

    /** Gunluk yuzdesel degisim orani (orn. 3.25 = %3.25 artis). */
    private double changePercent;

    /** Islem cirosu (TL cinsinden, hacim x fiyat). Siralama bu alana gore yapilir. */
    private double turnoverTL;

    /** Formatlanmis Turkce ciro degeri (orn. "1.87 Milyar", "543 Milyon"). */
    private String turnoverFormatted;
}
