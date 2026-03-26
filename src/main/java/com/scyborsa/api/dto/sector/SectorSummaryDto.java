package com.scyborsa.api.dto.sector;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Sektor ozet DTO'su.
 *
 * <p>Sektor listeleme sayfasi icin kullanilir. Her sektor icin
 * hisse sayisi, ortalama degisim yuzdesi ve en onemli hisselerin
 * detay bilgisini tasir.</p>
 *
 * @see com.scyborsa.api.service.SectorService
 * @see com.scyborsa.api.controller.SectorController
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SectorSummaryDto {

    /** Sektor slug degeri (orn. "bankacilik"). */
    private String slug;

    /** Turkce gorunen ad (orn. "Bankacilik"). */
    private String displayName;

    /** Sektor aciklama metni. */
    private String description;

    /** Remixicon ikon sinifi (orn. "ri-bank-line"). */
    private String icon;

    /** Sektordeki hisse sayisi. */
    private int stockCount;

    /** Sektordeki hisselerin ortalama degisim yuzdesi. */
    private double avgChangePercent;

    /** Sektordeki en yuksek degisime sahip top 3 hisse. */
    private List<TopStockInfo> topStocks;

    /**
     * Sektor icindeki bireysel hisse ozet bilgisi.
     *
     * <p>Dashboard sektor kartlarinda sektordeki en onemli hisselerin
     * fiyat, degisim ve hacim bilgilerini gostermek icin kullanilir.</p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopStockInfo {

        /** Hisse borsa kodu (orn. "ANHYT"). */
        private String ticker;

        /** Hisse aciklamasi (orn. "Anadolu Hayat Emeklilik"). */
        private String description;

        /** Son fiyat (orn. 124.10). */
        private double price;

        /** Gunluk degisim yuzdesi (orn. 5.08). */
        private double changePercent;

        /** Islem hacmi (orn. 2350000). */
        private double volume;

        /** Logo proxy icin logoid. */
        private String logoid;

        /** Katilim endeksi uyesi mi? */
        @JsonProperty("katilim")
        private boolean katilim;
    }
}
