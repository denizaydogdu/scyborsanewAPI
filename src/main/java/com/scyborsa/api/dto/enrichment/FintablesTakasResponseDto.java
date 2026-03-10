package com.scyborsa.api.dto.enrichment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * Fintables Takas (saklama dağılımı) API ham response DTO'su.
 *
 * <p>{@code /mobile/custodies/?index=custodian&code=XXX&date=YYYY-MM-DD}
 * endpoint'inden dönen veriyi temsil eder.</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FintablesTakasResponseDto {

    /** Veri tarihi (yyyy-MM-dd). */
    private String date;

    /** Saklama kuruluşu dağılım listesi (büyükten küçüğe sıralı). */
    private List<TakasItem> results;

    /**
     * Tek bir saklama kuruluşunun ham verisi.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TakasItem {

        /** Saklama kuruluşu kodu (ör: "TGB"). */
        private String custodian;

        /** TL cinsinden tutulan değer. */
        private double value;

        /** Yüzde payı (ondalık, ör: 0.76 = %76). */
        private double percentage;
    }
}
