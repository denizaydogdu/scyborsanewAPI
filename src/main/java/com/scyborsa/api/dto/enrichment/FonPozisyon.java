package com.scyborsa.api.dto.enrichment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fon pozisyon bilgisi.
 *
 * @see com.scyborsa.api.service.enrichment.FintablesFonPozisyonService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FonPozisyon {

    /** Fon kodu (orn. "TTKAK"). */
    private String fonKodu;

    /** Nominal lot sayisi. */
    private long nominal;

    /** Agirlik yuzdesi. */
    private double agirlik;
}
