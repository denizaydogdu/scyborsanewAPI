package com.scyborsa.api.dto.enrichment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fintables hisse ozet bilgisi.
 *
 * @see com.scyborsa.api.service.enrichment.FintablesSummaryService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FintablesSummaryDTO {

    /** Sektor adi. */
    private String sektorTitle;

    /** 1 yillik getiri verisi. */
    private YieldData yield1y;

    /**
     * Getiri verisi (dusuk/yuksek/baslangic).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class YieldData {
        /** Donem baslangic fiyati. */
        private Double first;
        /** Donem en dusuk fiyat. */
        private Double low;
        /** Donem en yuksek fiyat. */
        private Double high;
    }
}
