package com.scyborsa.api.service.telegram.infographic;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Piyasa özeti infografik kartı için veri taşıyıcı sınıf.
 *
 * <p>En çok yükselen, düşen ve hacimli hisselerin bilgilerini içerir.
 * {@link MarketSummaryCardRenderer} tarafından PNG kartı oluşturmak
 * için kullanılır.</p>
 *
 * @see MarketSummaryCardRenderer
 */
@Data
@Builder
public class MarketSummaryCardData {

    /** Kart zaman damgası ("HH:mm" formatında). */
    private String timestamp;

    /** En çok yükselen hisseler (maks 5). */
    private List<StockItem> risingStocks;

    /** En çok düşen hisseler (maks 5). */
    private List<StockItem> fallingStocks;

    /** En yüksek hacimli hisseler (maks 5). */
    private List<StockItem> volumeStocks;

    /**
     * Tek bir hissenin özet bilgisi.
     */
    @Data
    @Builder
    public static class StockItem {

        /** Hisse ticker'ı (ör. "BALAT"). */
        private String ticker;

        /** Günlük değişim yüzdesi (ör. +10.00). */
        private double changePercent;

        /** Son fiyat (ör. 103.40). */
        private double price;

        /** Türkçe formatlanmış hacim (ör. "90.6 Bin", "13 Milyon", "1.48 Milyar"). */
        private String volume;
    }
}
