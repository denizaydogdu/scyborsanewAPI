package com.scyborsa.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Teknik analiz yanıt DTO'su.
 * <p>
 * Belirli bir sembol ve periyot için TradingView screener'dan dönen
 * teknik gösterge verilerini taşır. {@link TvScreenerResponseModel} içinde
 * her periyot için ayrı bir instance oluşturulur.
 * </p>
 *
 * @see com.scyborsa.api.dto.screener.TvScreenerResponseModel
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TechnicalResponseDto {

    /** Periyot adı (ör. "1 Saatlik", "Günlük", "Haftalık"). */
    private String periodName;

    /** Teknik analiz yapılan hisse/sembol kodu (ör. "THYAO"). */
    private String symbol;

    /** Teknik gösterge sonuçları. Anahtar: gösterge adı, Değer: gösterge değeri. */
    private Map<String, Object> responseMap;
}
