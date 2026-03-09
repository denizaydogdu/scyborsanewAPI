package com.scyborsa.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sektor hisse bilgisi DTO'su.
 *
 * <p>Bir sektor endeksine ait hisselerin temel piyasa verilerini tasir.
 * TradingView Scanner API'den cekilen veri bu DTO'ya donusturulur.</p>
 *
 * @see com.scyborsa.api.service.SectorService
 * @see com.scyborsa.api.controller.SectorController
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SectorStockDto {

    /** Hisse borsa kodu (or. "GARAN"). */
    private String ticker;

    /** Hissenin aciklama/tam adi (or. "Garanti BBVA"). */
    private String description;

    /** Hissenin anlik fiyati (TL cinsinden). */
    private double price;

    /** Gunluk yuzdesel degisim orani (or. 2.35 = %2.35 artis). */
    private double changePercent;

    /** Gunluk islem hacmi. */
    private double volume;

    /** TradingView logo kimligi (or. "turk-hava-yollari"). */
    private String logoid;
}
