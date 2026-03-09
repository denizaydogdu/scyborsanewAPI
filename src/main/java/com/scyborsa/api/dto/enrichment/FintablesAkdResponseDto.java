package com.scyborsa.api.dto.enrichment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * Fintables AKD (Aracı Kurum Dağılımı) API ham response DTO'su.
 * {@code /mobile/akd/} endpoint'inden dönen JSON yapısını temsil eder.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FintablesAkdResponseDto {

    /** AKD sonuç listesi. Her bir eleman bir aracı kurumun dağılım verisini içerir. */
    private List<AkdItem> results;

    /** Sorgu başlangıç tarihi (yyyy-MM-dd HH:mm:ss formatında). */
    private String start;

    /** Sorgu bitiş tarihi (yyyy-MM-dd HH:mm:ss formatında). */
    private String end;

    /**
     * Tek bir aracı kurumun AKD verisi.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AkdItem {

        /** Aracı kurum kodu (ör: "ZRY", "GAR"). */
        private String brokerage;

        /** Net pozisyon metrikleri (net.size > 0 = alıcı, < 0 = satıcı). */
        private AkdMetric net;

        /** Toplam işlem metrikleri. */
        private AkdMetric total;
    }

    /**
     * AKD metrik verileri (net veya toplam için ortak yapı).
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AkdMetric {

        /** Lot sayısı. Net için pozitif=alıcı, negatif=satıcı. */
        private long size;

        /** Ortalama maliyet (TL). */
        private double cost;

        /** Pay oranı (0.0-1.0 arası, ör: 0.2503 = %25.03). */
        private double percentage;

        /** İşlem hacmi (TL). Sadece total bloğunda bulunur, net'te null olabilir. */
        private Long volume;
    }
}
