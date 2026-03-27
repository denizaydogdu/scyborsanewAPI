package com.scyborsa.api.service.telegram.infographic;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * AKD (Arac\u0131 Kurum Da\u011f\u0131l\u0131m\u0131) piyasa kurumsal \u00f6zet infografik kart\u0131 veri modeli.
 *
 * <p>Top al\u0131c\u0131 ve sat\u0131c\u0131 kurumlar\u0131n net hacim bilgilerini ta\u015f\u0131r.
 * {@link AkdSummaryCardRenderer} taraf\u0131ndan PNG g\u00f6r\u00fcnt\u00fcye d\u00f6n\u00fc\u015ft\u00fcr\u00fcl\u00fcr.</p>
 *
 * @see AkdSummaryCardRenderer
 */
@Data
@Builder
public class AkdSummaryCardData {

    /** Zaman damgas\u0131 ("HH:mm" format\u0131nda). */
    private String timestamp;

    /** En \u00e7ok alan kurumlar (maksimum 5). */
    private List<BrokerageItem> topBuyers;

    /** En \u00e7ok satan kurumlar (maksimum 5). */
    private List<BrokerageItem> topSellers;

    /** Toplam al\u0131c\u0131 kurum say\u0131s\u0131. */
    private long buyerCount;

    /** Toplam sat\u0131c\u0131 kurum say\u0131s\u0131. */
    private long sellerCount;

    /**
     * Tek bir arac\u0131 kurum \u00f6\u011fesi.
     */
    @Data
    @Builder
    public static class BrokerageItem {

        /** Kurum k\u0131sa ad\u0131 (\u00f6r. "Deniz", "Pusula"). */
        private String name;

        /** \u00d6nceden formatlanm\u0131\u015f T\u00fcrk\u00e7e hacim (\u00f6r. "2.01 Milyar", "997 Milyon"). */
        private String volume;
    }
}
