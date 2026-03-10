package com.scyborsa.api.dto.enrichment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Aracı Kurum AKD Listesi zenginleştirilmiş response DTO'su.
 * Fintables ham verisinin düzleştirilmiş ve yüzdeleri 0-100 aralığına dönüştürülmüş hali.
 * REST controller tarafından frontend'e döndürülür.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrokerageAkdListResponseDto {

    /** Aracı kurum AKD listesi (hacme göre sıralı). */
    private List<BrokerageAkdItemDto> items;

    /** Verinin ait olduğu tarih (yyyy-MM-dd formatında). */
    private String dataDate;

    /** Formatlanmış tarih (ör: "11 Mart 2026"). */
    private String formattedDataDate;

    /** Toplam aracı kurum sayısı. */
    private int totalCount;

    /**
     * Tek bir aracı kurumun düzleştirilmiş AKD verisi.
     * Alış/satış/net/toplam hacim ve yüzde değerlerini ayrı alanlar olarak içerir.
     * Yüzde değerleri 0-100 aralığındadır (servis katmanı ham 0-1 değerlerini dönüştürür).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BrokerageAkdItemDto {

        /** Aracı kurum kodu (ör: "YKY", "GAR"). */
        private String code;

        /** Aracı kurum tam adı (ör: "Yapı Kredi Yatırım"). */
        private String title;

        /** Aracı kurum kısa adı (ör: "Yapı Kredi"). */
        private String shortTitle;

        /** Aracı kurum logo URL'i. */
        private String logoUrl;

        /** Alış hacmi (TL cinsinden). */
        private long buyVolume;

        /** Alış yüzdesi (0-100 aralığında, ör: 14.2 = %14.2). */
        private double buyPercentage;

        /** Satış hacmi (TL cinsinden). */
        private long sellVolume;

        /** Satış yüzdesi (0-100 aralığında, ör: 14.8 = %14.8). */
        private double sellPercentage;

        /** Net hacim (TL cinsinden). Negatif değer net satıcı anlamına gelir. */
        private long netVolume;

        /** Net yüzdesi (0-100 aralığında). Negatif değer net satıcı anlamına gelir. */
        private double netPercentage;

        /** Toplam işlem hacmi (TL cinsinden, alış + satış). */
        private long totalVolume;

        /** Toplam işlem yüzdesi (0-100 aralığında, ör: 29.0 = %29.0). */
        private double totalPercentage;
    }
}
