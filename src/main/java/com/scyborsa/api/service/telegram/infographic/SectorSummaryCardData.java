package com.scyborsa.api.service.telegram.infographic;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Sektör özeti infografik kartı için veri taşıyıcı sınıf.
 *
 * <p>En çok yükselen ve düşen sektörlerin bilgilerini içerir.
 * {@link SectorSummaryCardRenderer} tarafından PNG kartı oluşturmak
 * için kullanılır.</p>
 *
 * @see SectorSummaryCardRenderer
 */
@Data
@Builder
public class SectorSummaryCardData {

    /** Kart zaman damgası ("HH:mm - dd MMMM yyyy" formatında). */
    private String timestamp;

    /** En çok yükselen sektörler (maks 5). */
    private List<SectorItem> risingSectors;

    /** En çok düşen sektörler (maks 5). */
    private List<SectorItem> fallingSectors;

    /**
     * Tek bir sektörün özet bilgisi.
     */
    @Data
    @Builder
    public static class SectorItem {

        /** Sektör görünen adı (ör. "Bankacılık"). */
        private String name;

        /** Ortalama değişim yüzdesi. */
        private double changePercent;

        /** Sektördeki hisse sayısı. */
        private int stockCount;
    }
}
