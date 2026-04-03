package com.scyborsa.api.dto.fintables;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fon portföy dağılım raporu sembol ağırlıkları DTO'su.
 *
 * <p>Fintables MCP {@code fon_portfoy_dagilim_raporu_sembol_agirliklari} tablosundaki
 * verileri temsil eder. Her satır bir fonun portföyündeki bir hissenin
 * ağırlık bilgisini içerir.</p>
 *
 * @see com.scyborsa.api.service.enrichment.FonPortfoyService
 * @see com.scyborsa.api.service.enrichment.FonPortfoySyncJob
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FonPortfoyDto {

    /** Portföy kalemi ID'si. */
    private Integer portfoyKalemiId;

    /** Fon kodu (ör: "TI2"). */
    private String fonKodu;

    /** Yüzdesel ağırlık (%). */
    private Double yuzdeselAgirlik;

    /** Fon portföy dağılım raporu ID'si. */
    private Integer fonPortfoyDagilimRaporuId;

    /** Fondaki lot miktarı. */
    private Double fondakiLot;
}
