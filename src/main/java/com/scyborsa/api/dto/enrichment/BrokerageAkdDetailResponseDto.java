package com.scyborsa.api.dto.enrichment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Aracı kurum AKD detay zenginleştirilmiş API yanıt DTO'su.
 *
 * <p>Controller tarafından döndürülen, Fintables ham verisinden dönüştürülmüş
 * ve UI kullanımına uygun hale getirilmiş AKD detay verisini temsil eder.</p>
 *
 * <p>Yüzde değerleri 0-100 aralığındadır (API'den gelen 0-1 değeri servis katmanında
 * 100 ile çarpılır).</p>
 *
 * @see FintablesBrokerageAkdDetailDto ham Fintables API yanıt DTO'su
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrokerageAkdDetailResponseDto {

    /** Aracı kurum kodu (örn: "GAR", "YAP"). */
    private String brokerageCode;

    /** Hisse bazlı AKD işlem verileri listesi. */
    private List<StockAkdItemDto> items;

    /** Veri tarihi (ISO format: "yyyy-MM-dd"). */
    private String dataDate;

    /** Formatlanmış veri tarihi (görüntüleme için, örn: "10 Mart 2026"). */
    private String formattedDataDate;

    /** Toplam alış hacmi (TL). */
    private long totalBuyVolume;

    /** Toplam satış hacmi (TL). */
    private long totalSellVolume;

    /** Toplam net hacim (TL, alış - satış). */
    private long totalNetVolume;

    /** İşlem yapılan hisse sayısı. */
    private int stockCount;

    /** Kurum başlığı (tam ad). */
    private String brokerageTitle;

    /** Kurum kısa başlığı. */
    private String brokerageShortTitle;

    /** Kurum logo URL'i. */
    private String brokerageLogoUrl;

    /**
     * Tek bir hissenin zenginleştirilmiş AKD işlem verisini temsil eder.
     *
     * <p>Hacim, lot adedi, yüzde ve maliyet bilgilerini alış/satış/net/toplam bazında içerir.
     * Yüzde değerleri 0-100 aralığındadır.</p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StockAkdItemDto {

        /** Hisse kodu (örn: "THYAO", "GARAN"). */
        private String code;

        /** Alış hacmi (TL). */
        private long buyVolume;

        /** Satış hacmi (TL). */
        private long sellVolume;

        /** Net hacim (TL, alış - satış). */
        private long netVolume;

        /** Toplam hacim (TL). */
        private long totalVolume;

        /** Alış yüzdesi (0-100 aralığında). */
        private double buyPercentage;

        /** Satış yüzdesi (0-100 aralığında). */
        private double sellPercentage;

        /** Net yüzde (0-100 aralığında). */
        private double netPercentage;

        /** Toplam yüzde (0-100 aralığında). */
        private double totalPercentage;

        /** Alış lot adedi. */
        private long buySize;

        /** Satış lot adedi. */
        private long sellSize;

        /** Net lot adedi (alış - satış). */
        private long netSize;

        /** Toplam lot adedi. */
        private long totalSize;

        /** Ortalama maliyet (TL, total.cost değeri). */
        private double cost;

        /** Hisse logo tanımlayıcısı (TradingView CDN). */
        private String logoid;
    }
}
