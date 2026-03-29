package com.scyborsa.api.dto.enrichment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Araci kurum takas (saklama) listesi response DTO'su.
 *
 * <p>Fintables RSC verisi zenginlestirilerek donusturulmus halidir.
 * Hacim degerleri Turkce formatlanir, haftalik degisim hesaplanir.</p>
 *
 * @see com.scyborsa.api.service.enrichment.BrokerageTakasListService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrokerageTakasListResponseDto {

    /** Araci kurum takas listesi. */
    private List<BrokerageTakasItemDto> items;

    /** Toplam kayit sayisi. */
    private int totalCount;

    /**
     * Tek araci kurum takas verisi.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BrokerageTakasItemDto {

        /** Araci kurum kodu. */
        private String code;

        /** Araci kurum tam adi. */
        private String title;

        /** Araci kurum kisa adi. */
        private String shortTitle;

        /** Logo URL'i. */
        private String logoUrl;

        /** Son saklama hacmi (ham deger). */
        private double lastValue;

        /** Formatlanmis son deger (orn: "1.87 Milyar"). */
        private String formattedLast;

        /** Onceki hafta degeri. */
        private double prevWeek;

        /** Onceki ay degeri. */
        private double prevMonth;

        /** Onceki 3 ay degeri. */
        private double prev3Months;

        /** Yuzde payi (0-100 araligi). */
        private double percentage;

        /** Haftalik degisim yuzdesi ((last-prevWeek)/prevWeek * 100). */
        private double weeklyChange;
    }
}
