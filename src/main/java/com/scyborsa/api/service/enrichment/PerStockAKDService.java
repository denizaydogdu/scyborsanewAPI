package com.scyborsa.api.service.enrichment;

import com.scyborsa.api.dto.enrichment.StockBrokerInfo;
import java.util.List;

/**
 * Hisse bazli AKD (Araci Kurum Dagitimi) servisi.
 */
public interface PerStockAKDService {

    /**
     * Hissenin kurum bazli alim/satim dagitimini getirir.
     *
     * @param stockName hisse kodu
     * @return kurum dagitim listesi (alici + satici)
     */
    List<StockBrokerInfo> getStockBrokerDistribution(String stockName);
}
