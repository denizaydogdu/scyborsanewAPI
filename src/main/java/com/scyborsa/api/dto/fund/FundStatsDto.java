package com.scyborsa.api.dto.fund;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * TEFAS fon istatistikleri DTO sinifi.
 *
 * <p>Toplam fon sayisi, yatirimci sayisi, portfoy buyuklugu gibi
 * ozet bilgileri tasir. Fon listesi sayfasindaki KPI kartlarinda kullanilir.</p>
 *
 * @see FundDto
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FundStatsDto {

    /** Toplam aktif fon sayisi. */
    private int totalActiveFunds;

    /** Toplam yatirimci sayisi. */
    private long totalInvestors;

    /** Toplam portfoy buyuklugu (TL). */
    private double totalPortfolioSize;

    /** Yatirim fonu sayisi. */
    private int yatFundCount;

    /** Emeklilik fonu sayisi. */
    private int emkFundCount;

    /** Borsa yatirim fonu sayisi. */
    private int byfFundCount;
}
