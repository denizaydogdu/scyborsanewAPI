package com.scyborsa.api.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Hacim lideri hisse bilgisi DTO'su.
 * <p>
 * En yüksek işlem hacmine sahip hisseleri temsil eder.
 * REST ve WebSocket üzerinden client'a döndürülür.
 * </p>
 */
@Data
@Builder
public class VolumeLeaderDto {

    /** Hisse borsa kodu (ör. "THYAO", "GARAN"). */
    private String ticker;

    /** Hissenin açıklama/tam adı (ör. "Türk Hava Yolları"). */
    private String description;

    /** Hissenin anlık fiyatı (TL cinsinden). */
    private Double price;

    /** Günlük yüzdesel değişim oranı (ör. 3.25 = %3.25 artış). */
    private Double changePercent;

    /** İşlem hacmi (lot cinsinden). Sıralama bu alana göre yapılır. */
    private Long volume;
}
