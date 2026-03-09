package com.scyborsa.api.dto.enrichment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Hisse bazli kurum dagitimi bilgisi.
 *
 * @see com.scyborsa.api.service.enrichment.PerStockAKDService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockBrokerInfo {

    /** Kurum adi. */
    private String brokerName;

    /** Islem emojisi (🟢 alim, 🔴 satim). */
    private String emoji;

    /** Formatli hacim (orn. "+2.0 Milyar"). */
    private String formattedVolume;
}
