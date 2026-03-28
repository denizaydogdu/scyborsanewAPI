package com.scyborsa.api.dto.watchlist;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Takip listesindeki hisse veri transfer nesnesi.
 *
 * <p>Bir takip listesine eklenmis hissenin bilgilerini tasir.
 * {@code lastPrice}, {@code change}, {@code changePercent} ve {@code volume}
 * alanlari sorgu zamaninda zenginlestirilir (DB'de saklanmaz).</p>
 *
 * @see com.scyborsa.api.model.WatchlistItem
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WatchlistStockDto {

    /** Takip listesi item ID'si. */
    private Long id;

    /** Hisse borsa kodu (orn. "THYAO"). */
    private String stockCode;

    /** Hisse gorunen adi. */
    private String stockName;

    /** Gorunum sirasi. */
    private Integer displayOrder;

    /** Son fiyat (sorgu zamaninda zenginlestirilir). */
    private Double lastPrice;

    /** Gunluk degisim (TL, sorgu zamaninda zenginlestirilir). */
    private Double change;

    /** Gunluk degisim yuzdesi (sorgu zamaninda zenginlestirilir). */
    private Double changePercent;

    /** Islem hacmi (sorgu zamaninda zenginlestirilir). */
    private Double volume;

    /** Hisse logo ID'si (TradingView logoid, sorgu zamaninda zenginlestirilir). */
    private String logoid;
}
