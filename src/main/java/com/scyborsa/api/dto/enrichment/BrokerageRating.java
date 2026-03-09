package com.scyborsa.api.dto.enrichment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Araci kurum model portfoly tavsiyesi.
 *
 * @see com.scyborsa.api.service.enrichment.FintablesAnalystService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrokerageRating {

    /** Araci kurum adi. */
    private String brokerName;

    /** Hedef fiyat. */
    private Double targetPrice;

    /**
     * Formatli gosterim (orn. "Unlu (Hedef: 98.8TL)").
     *
     * @return formatli string
     */
    public String formatli() {
        String name = brokerName != null ? brokerName : "Bilinmeyen Kurum";
        if (targetPrice != null) {
            return String.format("%s (Hedef: %.1f₺)", name, targetPrice);
        }
        return name;
    }
}
