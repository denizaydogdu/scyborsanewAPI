package com.scyborsa.api.dto.fintables;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Şirket guidance (beklenti) DTO'su.
 *
 * <p>Fintables MCP {@code guidance} tablosundaki verileri temsil eder.
 * Her satır bir şirketin belirli bir yıl için açıkladığı
 * beklentileri içerir.</p>
 *
 * @see com.scyborsa.api.service.enrichment.GuidanceService
 * @see com.scyborsa.api.service.enrichment.GuidanceSyncJob
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuidanceDto {

    /** Hisse senedi kodu (ör: "GARAN"). */
    private String hisseSenediKodu;

    /** Guidance yılı. */
    private Integer yil;

    /** Şirketin açıkladığı beklentiler metni. */
    private String beklentiler;
}
