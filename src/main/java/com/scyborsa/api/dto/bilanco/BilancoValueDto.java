package com.scyborsa.api.dto.bilanco;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bilanco parasal deger DTO'su.
 *
 * <p>Bir bilanco kaleminin parasal degerini ve para birimini tasir.
 * {@code numericAmount} alani sayisal karsilastirma icin kullanilir.</p>
 *
 * @see BilancoTableItemDto
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BilancoValueDto {

    /** Tutarin metin gosterimi (orn. "1.234.567"). */
    private String amount;

    /** Para birimi (orn. "TRY", "USD"). */
    private String currency;

    /** Sayisal tutar degeri (hesaplama icin). */
    private Double numericAmount;
}
