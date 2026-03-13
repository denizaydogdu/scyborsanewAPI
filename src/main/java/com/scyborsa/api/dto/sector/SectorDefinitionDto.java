package com.scyborsa.api.dto.sector;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Sektor tanim DTO'su.
 *
 * <p>{@code sector-definitions.json} dosyasindan deserialize edilerek
 * sektor bilgilerini tasir. TradingView sector/industry eslesmesi veya
 * ticker tabanli filtreleme icin gerekli tum alanlari icerir.</p>
 *
 * @see com.scyborsa.api.config.SectorDefinitionRegistry
 * @see com.scyborsa.api.service.SectorService
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SectorDefinitionDto {

    /** URL path variable olarak kullanilan slug (orn. "bankacilik"). */
    private String slug;

    /** Turkce gorunen ad (orn. "Bankacilik"). */
    private String displayName;

    /** Sektor aciklama metni. */
    private String description;

    /** Remixicon ikon sinifi (orn. "ri-bank-line"). */
    private String icon;

    /** TradingView sector filtresi (orn. "Finance"). Ticker tabanli sektorlerde null. */
    private String tvSector;

    /** TradingView industry filtresi listesi. null ise tum sector eslenir. */
    private List<String> tvIndustries;

    /** Ticker tabanli filtreleme listesi. null degilse tvSector/tvIndustries yerine kullanilir. */
    private List<String> tickers;

    /** Eski slug degerleri (geriye donuk uyumluluk icin). */
    private List<String> legacySlugs;

    /** Siralama numarasi. */
    private int siraNo;
}
