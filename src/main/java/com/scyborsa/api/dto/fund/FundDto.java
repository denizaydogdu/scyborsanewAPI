package com.scyborsa.api.dto.fund;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * TEFAS fon verileri DTO sinifi.
 *
 * <p>api.velzon.tr uzerindeki TEFAS Fund API'den donen fon bilgilerini tasir.
 * Fon listesi sayfasinda kullanilir.</p>
 *
 * @see FundStatsDto
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FundDto {

    /** TEFAS fon kodu (orn. "TEB"). */
    private String tefasCode;

    /** Fon adi. */
    private String fundName;

    /** Fon tipi: YAT (Yatirim), EMK (Emeklilik), BYF (Borsa Yatirim Fonu). */
    private String fundType;

    /** Fon kategorisi. */
    private String fundCategory;

    /** Fon aktif mi? (Velzon: "isActive"). */
    @JsonAlias("isActive")
    private boolean active;

    /** Katilim fonu mu? (Velzon: "isParticipation"). */
    @JsonAlias("isParticipation")
    private boolean participation;

    /** Son NAV (Net Aktif Deger, nullable). */
    private Double latestPrice;

    /** Portfoy buyuklugu (TL, nullable). */
    private Double portfolioSize;

    /** Yatirimci sayisi (nullable). */
    private Integer investorCount;

    /** Risk seviyesi (1-7, nullable). */
    private Integer riskLevel;

    /** Gunluk getiri (%). */
    private Double return1D;

    /** Haftalik getiri (%). */
    private Double return1W;

    /** 1 aylik getiri (%). */
    private Double return1M;

    /** 3 aylik getiri (%). */
    private Double return3M;

    /** 6 aylik getiri (%). */
    private Double return6M;

    /** Yil basi getiri (%). */
    private Double returnYTD;

    /** 1 yillik getiri (%). */
    private Double return1Y;

    /** 3 yillik getiri (%). */
    private Double return3Y;

    /** 5 yillik getiri (%). */
    private Double return5Y;
}
