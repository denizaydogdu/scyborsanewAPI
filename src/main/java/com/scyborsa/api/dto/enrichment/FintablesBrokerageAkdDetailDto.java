package com.scyborsa.api.dto.enrichment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Fintables API'den dönen aracı kurum AKD detay yanıt DTO'su.
 *
 * <p>{@code /mobile/brokerages/akd/} endpoint'inden dönen ham JSON yapısını temsil eder.
 * Her bir hisse için alış, satış, net ve toplam işlem verilerini içerir.</p>
 *
 * @see BrokerageAkdDetailResponseDto zenginleştirilmiş API yanıt DTO'su
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FintablesBrokerageAkdDetailDto {

    /** Hisse bazlı AKD işlem verileri listesi. */
    private List<StockAkdItem> results;

    /** Veri başlangıç tarihi (format: "yyyy-MM-dd HH:mm:ss"). */
    private String start;

    /** Veri bitiş tarihi (format: "yyyy-MM-dd HH:mm:ss"). */
    private String end;

    /** Tüm hisseler için toplam alış hacmi (TL). */
    @JsonProperty("total_buy_volume")
    private double totalBuyVolume;

    /** Tüm hisseler için toplam satış hacmi (TL). */
    @JsonProperty("total_sell_volume")
    private double totalSellVolume;

    /**
     * Tek bir hissenin AKD işlem verisini temsil eder.
     *
     * <p>Alış (buy), satış (sell), net ve toplam (total) işlem detaylarını içerir.</p>
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StockAkdItem {

        /** Hisse kodu (örn: "THYAO", "GARAN"). */
        private String code;

        /** Alış işlem verileri. */
        private SizeVolumeData buy;

        /** Satış işlem verileri. */
        private SizeVolumeData sell;

        /** Net işlem verileri (alış - satış). */
        private SizeVolumeData net;

        /** Toplam işlem verileri. */
        private SizeVolumeData total;
    }

    /**
     * AKD işlem verisi alt bileşeni.
     *
     * <p>Lot adedi, hacim (TL), ortalama maliyet ve yüzde bilgisi içerir.</p>
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SizeVolumeData {

        /** İşlem lot adedi. */
        private long size;

        /** İşlem hacmi (TL). */
        private long volume;

        /** Ortalama maliyet (TL). */
        private double cost;

        /** İşlem yüzde oranı (0-1 aralığında, örn: 0.1458 = %14.58). */
        private double percentage;
    }
}
