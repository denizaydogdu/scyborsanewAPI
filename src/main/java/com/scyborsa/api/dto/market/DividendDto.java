package com.scyborsa.api.dto.market;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Temettu (dividend) veri transfer nesnesi.
 *
 * <p>Velzon API'sinden donen temettu bilgilerini temsil eder.
 * Yaklaşan ve gecmis temettu dagitimlarini icerir.</p>
 *
 * @see com.scyborsa.api.service.market.DividendService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DividendDto {

    /** Hisse kodu (orn. "THYAO"). */
    private String stockCode;

    /** Sirket adi (orn. "Türk Hava Yolları A.O."). */
    private String companyName;

    /** Hisse basina temettu tutari (TRY). */
    private Double dividendAmount;

    /** Temettu verimi (yuzde olarak, orn. 5.2). */
    private Double dividendYield;

    /** Temettu hak edis tarihi (ISO format, orn. "2026-04-15"). */
    private String exDividendDate;

    /** Temettu odeme tarihi (ISO format, orn. "2026-05-01"). */
    private String paymentDate;

    /** Para birimi (varsayilan "TRY"). */
    private String currency;

    /** Hissenin katılım endeksinde olup olmadığı. */
    private boolean katilim;
}
