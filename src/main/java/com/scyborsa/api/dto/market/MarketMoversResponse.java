package com.scyborsa.api.dto.market;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Piyasa hareket ettiricileri toplu yanıt DTO'su.
 * <p>
 * En çok yükselen ve en çok düşen hisseleri bir arada döndürmek için kullanılır.
 * REST endpoint'i ve WebSocket kanalı üzerinden client'a iletilir.
 * </p>
 *
 * @see MarketMoverDto
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class MarketMoversResponse {

    /** En çok yükselen hisseler listesi. */
    private List<MarketMoverDto> rising;

    /** En çok düşen hisseler listesi. */
    private List<MarketMoverDto> falling;

    /** Verinin oluşturulma zamanı (epoch milisaniye). Cache geçerliliği için kullanılır. */
    private long timestamp;
}
