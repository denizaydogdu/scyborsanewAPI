package com.scyborsa.api.service.enrichment;

import com.scyborsa.api.dto.enrichment.FonPozisyon;
import java.util.List;

/**
 * Fintables fon pozisyon servisi.
 */
public interface FintablesFonPozisyonService {

    /**
     * Hisseyi tutan fonlarin pozisyon bilgilerini getirir.
     *
     * @param stockName hisse kodu
     * @return fon pozisyon listesi
     */
    List<FonPozisyon> getFonPozisyonlari(String stockName);
}
