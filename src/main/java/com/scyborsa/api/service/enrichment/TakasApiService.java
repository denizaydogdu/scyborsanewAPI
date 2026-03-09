package com.scyborsa.api.service.enrichment;

import com.scyborsa.api.dto.enrichment.TakasCustodianDTO;
import java.time.LocalDate;
import java.util.List;

/**
 * Takas/saklama dagitimi servisi.
 */
public interface TakasApiService {

    /**
     * Hissenin saklama kurulsu dagitimini getirir.
     *
     * @param stockName hisse kodu
     * @param date tarih
     * @return saklama dagitim listesi
     */
    List<TakasCustodianDTO> getCustodyData(String stockName, LocalDate date);

    /**
     * Servis aktif mi? Impl'lar config uzerinden devre disi birakabilir.
     * Default: false — impl bean kayitli olsa bile acikca aktif etmedikce calismaz.
     * Impl siniflari bu metodu override edip {@code true} donmeli.
     *
     * @return aktifse true
     */
    default boolean isEnabled() { return false; }
}
