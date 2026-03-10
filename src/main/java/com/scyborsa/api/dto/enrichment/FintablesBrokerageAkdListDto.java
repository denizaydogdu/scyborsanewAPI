package com.scyborsa.api.dto.enrichment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Fintables Aracı Kurum AKD Listesi API ham response DTO'su.
 * {@code /mobile/akd/} endpoint'inden tüm aracı kurumların toplu AKD verisini döner.
 * Hisse bazlı değil, tüm piyasa genelindeki aracı kurum dağılım verisini temsil eder.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FintablesBrokerageAkdListDto {

    /** AKD sonuç listesi. Her eleman bir aracı kurumun alış/satış/net/toplam hacim verisini içerir. */
    private List<BrokerageAkdItem> results;

    /** Sorgu başlangıç tarihi (yyyy-MM-dd formatında). */
    private String start;

    /** Sorgu bitiş tarihi (yyyy-MM-dd formatında). */
    private String end;

    /**
     * Tek bir aracı kurumun toplu AKD verisi.
     * Alış, satış, net ve toplam hacim ile yüzde bilgilerini içerir.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BrokerageAkdItem {

        /** Aracı kurum tam adı (ör: "Yapı Kredi Yatırım"). */
        private String title;

        /** Aracı kurum kısa adı (ör: "Yapı Kredi"). */
        @JsonProperty("short_title")
        private String shortTitle;

        /** Aracı kurum logo URL'i (ör: "https://cdn.fintables.com/brokerages/logos/yapi-kredi.png"). */
        private String logo;

        /** Aracı kurum kodu (ör: "YKY", "GAR"). */
        private String code;

        /** Satış hacim ve yüzde verisi. */
        private VolumePercentage sell;

        /** Alış hacim ve yüzde verisi. */
        private VolumePercentage buy;

        /** Net hacim ve yüzde verisi (alış - satış). Negatif değer net satıcı anlamına gelir. */
        private VolumePercentage net;

        /** Toplam işlem hacim ve yüzde verisi (alış + satış). */
        private VolumePercentage total;
    }

    /**
     * Hacim ve yüzde çifti.
     * Fintables API'den gelen ham yüzde değeri 0-1 aralığındadır (ör: 0.148 = %14.8).
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VolumePercentage {

        /** İşlem hacmi (TL cinsinden). */
        private long volume;

        /** Yüzde oranı (0.0-1.0 arası, ör: 0.148 = %14.8). */
        private double percentage;
    }
}
