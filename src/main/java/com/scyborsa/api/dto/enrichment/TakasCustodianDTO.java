package com.scyborsa.api.dto.enrichment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Takas saklama kurulsu dagitim bilgisi.
 *
 * @see com.scyborsa.api.service.enrichment.TakasApiService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TakasCustodianDTO {

    /** Saklama kurulsu kodu (orn. "TGB"). */
    private String custodianCode;

    /** Tutulan deger (TL). */
    private Double value;

    /** Yuzde payi [0.0 - 1.0 arasinda, orn. 0.15 = %15]. */
    private Double percentage;

    /**
     * Formatli deger (orn. "1.87 Milyar TL").
     *
     * @return formatli string
     */
    public String getFormattedValue() {
        if (value == null) return "0 TL";
        if (value >= 1_000_000_000) {
            return String.format("%.2f Milyar TL", value / 1_000_000_000.0);
        } else if (value >= 1_000_000) {
            return String.format("%.2f Milyon TL", value / 1_000_000.0);
        } else if (value >= 1_000) {
            return String.format("%.2f Bin TL", value / 1_000.0);
        }
        return String.format("%.2f TL", value);
    }
}
