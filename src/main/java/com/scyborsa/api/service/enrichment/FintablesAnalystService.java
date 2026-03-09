package com.scyborsa.api.service.enrichment;

import com.scyborsa.api.dto.enrichment.BrokerageRating;
import java.util.List;

/**
 * Fintables araci kurum analist tavsiyesi servisi.
 */
public interface FintablesAnalystService {

    /**
     * Hissenin model portfoly tavsiyelerini getirir.
     *
     * @param stockName hisse kodu
     * @return tavsiye listesi
     */
    List<BrokerageRating> getModelPortfolio(String stockName);
}
