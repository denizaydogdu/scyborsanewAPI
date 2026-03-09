package com.scyborsa.api.dto;

import lombok.Data;

/**
 * Grafik verisi abonelik isteği DTO'su.
 * <p>
 * WebSocket üzerinden belirli bir sembol ve periyot için mum (candle) verisi
 * aboneliği başlatmak amacıyla kullanılır. Client tarafından gönderilir.
 * </p>
 *
 * @see com.scyborsa.api.model.chart.BarUpdateMessage
 */
@Data
public class ChartSubscribeRequest {

    /** Abone olunacak hisse/sembol kodu (ör. "THYAO", "GARAN"). */
    private String symbol;

    /** Mum periyodu (dakika cinsinden). Varsayılan: "30" (30 dakikalık mumlar). */
    private String period = "30";

    /** İlk yüklemede istenecek geçmiş mum sayısı. Varsayılan: 300. */
    private int bars = 300;
}
