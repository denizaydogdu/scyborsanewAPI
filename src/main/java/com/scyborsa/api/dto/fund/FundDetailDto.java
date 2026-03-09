package com.scyborsa.api.dto.fund;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * TEFAS fon detay DTO sinifi.
 *
 * <p>api.velzon.tr uzerindeki {@code GET /api/funds/{code}/detail} endpoint'inden
 * donen kapsamli fon bilgilerini tasir. Fon detay sayfasinda kullanilir.</p>
 *
 * <p>Temel fon bilgilerine ek olarak varlik dagilimi, portfoy pozisyonlari,
 * benzer fonlar, kategori siralamalari, sektor agirliklari, benchmark
 * karsilastirmasi ve fiyat gecmisi bilgilerini icerir.</p>
 *
 * @see FundDto
 * @see FundTimeSeriesDto
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FundDetailDto {

    // ===== Temel fon bilgileri (FundDto ile ayni 19 alan) =====

    /** TEFAS fon kodu (orn. "TEB"). */
    private String tefasCode;

    /** Fon adi. */
    private String fundName;

    /** Fon tipi: YAT (Yatirim), EMK (Emeklilik), BYF (Borsa Yatirim Fonu). */
    private String fundType;

    /** Fon kategorisi. */
    private String fundCategory;

    /** Fon aktif mi? (Velzon: "isActive"). */
    @JsonAlias("isActive")
    private boolean active;

    /** Katilim fonu mu? (Velzon: "isParticipation"). */
    @JsonAlias("isParticipation")
    private boolean participation;

    /** Son NAV (Net Aktif Deger, nullable). */
    private Double latestPrice;

    /** Portfoy buyuklugu (TL, nullable — Velzon null gonderebilir). */
    private Double portfolioSize;

    /** Yatirimci sayisi (nullable — Velzon null gonderebilir). */
    private Integer investorCount;

    /** Risk seviyesi (1-7, nullable). */
    private Integer riskLevel;

    /** Gunluk getiri (%). */
    private Double return1D;

    /** Haftalik getiri (%). */
    private Double return1W;

    /** 1 aylik getiri (%). */
    private Double return1M;

    /** 3 aylik getiri (%). */
    private Double return3M;

    /** 6 aylik getiri (%). */
    private Double return6M;

    /** Yil basi getiri (%). */
    private Double returnYTD;

    /** 1 yillik getiri (%). */
    private Double return1Y;

    /** 3 yillik getiri (%). */
    private Double return3Y;

    /** 5 yillik getiri (%). */
    private Double return5Y;

    // ===== Ek detay alanlari =====

    /** Fon kurucu sirket. */
    private String founder;

    /** Yonetim ucreti orani (%). */
    private Double managementFee;

    /** Alis valor suresi (gun). */
    private Integer buyingValor;

    /** Satis valor suresi (gun). */
    private Integer sellingValor;

    /** Fon strateji aciklamasi. */
    private String strategyStatement;

    /** Kategori adi. */
    private String categoryName;

    /** Kategori kodu. */
    private String categoryCode;

    /** Bilesik yillik buyume orani (CAGR, %). */
    private Double cagr;

    /** Toplam pay sayisi. */
    private Long totalShares;

    /** Fiyat tarihi (orn. "2026-03-05"). */
    private String priceDate;

    /** Emeklilik fonu mu? (Velzon: "isRetirement"). */
    @JsonAlias("isRetirement")
    private boolean retirement;

    // ===== Ic yapılar =====

    /** Varlik dagilimi bilgisi. */
    private Allocation allocation;

    /** Portfoy pozisyon listesi. */
    private List<Holding> holdings;

    /** Benzer fonlar listesi. */
    private List<SimilarFund> similarFunds;

    /** Kategori siralaması bilgisi. */
    private CategoryRank categoryRank;

    /** Sektor agirliklari listesi (Velzon: "sectors"). */
    @JsonAlias("sectors")
    private List<SectorWeight> sectorWeights;

    /** Benchmark karsilastirma bilgisi (Velzon: "benchmarks"). */
    @JsonAlias("benchmarks")
    private BenchmarkInfo benchmark;

    /** Fiyat gecmisi listesi. */
    private List<PricePoint> priceHistory;

    // ===== Inner Class'lar =====

    /**
     * Fon varlik dagilimi (asset allocation) bilgisi.
     *
     * <p>Hisse senedi, tahvil, nakit, repo, doviz ve diger
     * varlik siniflarinin portfoy icindeki yuzdelerini tasir.</p>
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Allocation {

        /** Hisse senedi orani (%). */
        private Double stockPercentage;

        /** Tahvil/bono orani (%). */
        private Double bondPercentage;

        /** Nakit orani (%). */
        private Double cashPercentage;

        /** Repo orani (%). */
        private Double repoPercentage;

        /** Doviz orani (%). */
        private Double foreignCurrencyPercentage;

        /** Diger varlik orani (%). */
        private Double otherPercentage;

        /** Dagilim tarihi (orn. "2026-03-05"). */
        private String allocationDate;
    }

    /**
     * Fon portfoyundeki tekil pozisyon (holding) bilgisi.
     *
     * <p>Portfoyde yer alan hisse senedi, tahvil veya diger
     * yatirim araclarina ait ad, ticker, agirlik ve piyasa degeri.</p>
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Holding {

        /** Pozisyon adi (Velzon API'de "assetName" olarak gelir). */
        @JsonAlias("assetName")
        private String name;

        /** Borsa ticker kodu (Velzon API'de "assetCode" olarak gelir). */
        @JsonAlias("assetCode")
        private String ticker;

        /** Portfoy icindeki agirlik (%, Velzon API'de "weight" olarak gelir). */
        @JsonAlias("weight")
        private Double percentage;

        /** Piyasa degeri (TL). */
        private Double marketValue;

        /** Onceki ay agirligi (%). */
        private Double lastMonthWeight;

        /** Agirlik degisimi (%). */
        private Double weightChange;
    }

    /**
     * Benzer fon bilgisi.
     *
     * <p>Ayni kategori veya benzer strateji izleyen fonlarin
     * karsilastirmali getiri bilgilerini tasir.</p>
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SimilarFund {

        /** TEFAS fon kodu. */
        private String tefasCode;

        /** Fon adi. */
        private String fundName;

        /** Fon tipi. */
        private String fundType;

        /** 1 aylik getiri (%). */
        private Double return1M;

        /** 3 aylik getiri (%). */
        private Double return3M;

        /** 1 yillik getiri (%). */
        private Double return1Y;

        /** Benzerlik skoru (0-1 arasi). */
        private Double similarityScore;

        /** Benzerlik nedenleri. */
        private List<String> matchReasons;
    }

    /**
     * Fon kategori siralama bilgisi.
     *
     * <p>Fonun kendi kategorisi icindeki performans siralamasini gosterir.</p>
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CategoryRank {

        /** Siralama pozisyonu. */
        private Integer rank;

        /** Kategorideki toplam fon sayisi (Velzon: "totalInCategory"). */
        @JsonAlias("totalInCategory")
        private Integer totalFunds;

        /** Siralama kriteri/donemi (Velzon: "criteria"). */
        @JsonAlias("criteria")
        private String period;
    }

    /**
     * Sektor agirligi bilgisi.
     *
     * <p>Fonun hisse senedi portfoyundeki sektorel dagilimi gosterir.</p>
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SectorWeight {

        /** Sektor adi (orn. "Bankacılık", Velzon: "name"). */
        @JsonAlias("name")
        private String sectorName;

        /** Sektor agirligi (%, Velzon: "weight"). */
        @JsonAlias("weight")
        private Double percentage;
    }

    /**
     * Benchmark karsilastirma bilgisi.
     *
     * <p>Fonun referans alindigi benchmark endeksinin getiri bilgilerini tasir.</p>
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BenchmarkInfo {

        /** Benchmark adi (orn. "BIST 100"). */
        private String name;

        /** 1 aylik getiri (%). */
        private Double return1M;

        /** 3 aylik getiri (%). */
        private Double return3M;

        /** 1 yillik getiri (%). */
        private Double return1Y;
    }

    /**
     * Fiyat gecmisi veri noktasi.
     *
     * <p>Fonun belirli bir tarihteki NAV (Net Aktif Deger) bilgisini tasir.
     * Fiyat grafigi ciziminde kullanilir.</p>
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PricePoint {

        /** Tarih (orn. "2026-03-05"). */
        private String date;

        /** NAV degeri (Velzon API'de "price" olarak gelir). */
        @JsonAlias("price")
        private Double value;

        /** Gunluk degisim orani (%). */
        private Double change;
    }
}
