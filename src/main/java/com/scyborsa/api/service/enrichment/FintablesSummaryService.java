package com.scyborsa.api.service.enrichment;

import com.scyborsa.api.dto.enrichment.FintablesSummaryDTO;

/**
 * Fintables hisse ozet bilgisi servisi (sektor + getiri).
 */
public interface FintablesSummaryService {

    /**
     * Hissenin sektor ve getiri ozet bilgilerini getirir.
     *
     * @param stockName hisse kodu
     * @return ozet bilgi
     */
    FintablesSummaryDTO getStockSummary(String stockName);
}
