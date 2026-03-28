package com.scyborsa.api.dto.watchlist;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Takip listesi anlik fiyat guncelleme DTO'su (STOMP broadcast payload).
 *
 * <p>WebSocket uzerinden kullanicilara gonderilen anlik fiyat
 * guncelleme mesajini tasir. QuotePriceCache tick'lerinden uretilir.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WatchlistPriceUpdateDto {

    /** Hisse borsa kodu (orn. "THYAO"). */
    private String stockCode;

    /** Son fiyat. */
    private Double lastPrice;

    /** Gunluk degisim (TL). */
    private Double change;

    /** Gunluk degisim yuzdesi. */
    private Double changePercent;

    /** Islem hacmi. */
    private Double volume;

    /** Guncelleme zamani (epoch millis). */
    private Long updateTime;
}
