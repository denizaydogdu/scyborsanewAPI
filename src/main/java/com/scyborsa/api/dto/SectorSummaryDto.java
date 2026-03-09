package com.scyborsa.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sektor ozet DTO'su.
 *
 * <p>Sektor listeleme sayfasi icin kullanilir. Her sektor icin
 * hisse sayisi ve ortalama degisim yuzdesi bilgisini tasir.</p>
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
}
