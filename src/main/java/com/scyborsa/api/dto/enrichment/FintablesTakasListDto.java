package com.scyborsa.api.dto.enrichment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Fintables RSC endpoint'inden gelen araci kurum takas (saklama) listesi DTO'su.
 *
 * <p>Fintables Next.js RSC response'undan parse edilen ham veridir.
 * {@code https://fintables.com/araci-kurumlar/takas-analizi} endpoint'inden gelir.</p>
 *
 * @see com.scyborsa.api.service.enrichment.BrokerageTakasListService
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FintablesTakasListDto {

    /** Araci kurum kodu (orn: "MLB", "YKR"). */
    private String code;

    /** Araci kurum tam adi. */
    private String title;

    /** Araci kurum kisa adi. */
    @JsonProperty("short_title")
    private String shortTitle;

    /** Logo URL'i. */
    private String logo;

    /** Halka acik sirket adi (nullable). */
    @JsonProperty("public_company")
    private String publicCompany;

    /** Borsada islem goren araci kurum mu? */
    @JsonProperty("is_listed")
    private boolean listed;

    /** Son deger (saklama hacmi). */
    private double last;

    /** Onceki hafta degeri. */
    @JsonProperty("prev_week")
    private double prevWeek;

    /** Onceki ay degeri. */
    @JsonProperty("prev_month")
    private double prevMonth;

    /** Onceki 3 ay degeri. */
    @JsonProperty("prev_3_months")
    private double prev3Months;

    /** Yuzde payi (0-1 araligi, orn: 0.1234 = %12.34). */
    private double percentage;
}
