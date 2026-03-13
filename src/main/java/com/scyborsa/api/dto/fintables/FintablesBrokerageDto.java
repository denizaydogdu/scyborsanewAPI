package com.scyborsa.api.dto.fintables;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fintables brokerages API response DTO sinifi.
 *
 * <p>Fintables {@code /brokerages/} endpoint'inden donen araci kurum
 * bilgilerini temsil eder. JSON field isimleri {@code @JsonProperty}
 * ile eslenir.</p>
 *
 * @see com.scyborsa.api.service.client.FintablesApiClient#getBrokerages()
 * @see com.scyborsa.api.service.AraciKurumService#syncFromBrokerageList(java.util.List)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FintablesBrokerageDto {

    /** Araci kurum kodu. Orn: "isyatirim", "garanti". */
    private String code;

    /** Araci kurum tam adi. Orn: "Is Yatirim Menkul Degerler A.S.". */
    private String title;

    /** Araci kurum logo URL'i. */
    private String logo;

    /** Araci kurumun bagli oldugu halka acik sirket kodu. Orn: "ISMEN", "GARAN". */
    @JsonProperty("public_company")
    private String publicCompany;

    /** Araci kurum kisa adi. Orn: "Is Yatirim". */
    @JsonProperty("short_title")
    private String shortTitle;

    /** Araci kurumun borsada islem gorup gormedigini belirtir. */
    @JsonProperty("is_listed")
    private Boolean isListed;
}
