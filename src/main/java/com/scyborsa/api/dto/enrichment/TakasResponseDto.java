package com.scyborsa.api.dto.enrichment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Zenginleştirilmiş Takas (saklama dağılımı) API response DTO'su.
 *
 * <p>Fintables ham verisine DB'den kurum adı ve logo bilgisi eklenerek oluşturulur.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TakasResponseDto {

    /** Zenginleştirilmiş saklama kuruluşu listesi. */
    private List<TakasCustodianEnrichedDto> custodians;

    /** Verinin ait olduğu tarih (yyyy-MM-dd formatında). */
    private String dataDate;

    /** Formatlanmış tarih (ör: "11 Mart 2026"). */
    private String formattedDataDate;

    /**
     * Tek bir saklama kuruluşunun zenginleştirilmiş verisi.
     * DB'den kurum adı, kısa adı ve logo URL bilgisi eklenir.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TakasCustodianEnrichedDto {

        /** Saklama kuruluşu kodu (ör: "TGB"). */
        private String code;

        /** Kurum tam adı (DB'den). */
        private String title;

        /** Kurum kısa adı (DB'den). */
        private String shortTitle;

        /** Kurum logo URL (DB'den). */
        private String logoUrl;

        /** TL cinsinden tutulan değer (ham). */
        private double deger;

        /** Formatlanmış değer (ör: "1.87 Milyar TL"). */
        private String formattedDeger;

        /** Yüzde oranı (0-100 arası, ör: 76.01). */
        private double yuzde;
    }
}
