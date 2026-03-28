package com.scyborsa.api.dto.watchlist;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Takip listesine hisse ekleme istegi DTO'su.
 *
 * <p>Kullanicinin bir takip listesine hisse eklerken gonderdigi verileri tasir.</p>
 *
 * @see com.scyborsa.api.model.WatchlistItem
 */
@Data
public class AddStockRequest {

    /** Hisse borsa kodu (orn. "THYAO"). Zorunlu, maks 10 karakter. */
    @NotBlank
    @Size(max = 10)
    private String stockCode;

    /** Hisse gorunen adi (opsiyonel, maks 100 karakter). */
    @Size(max = 100)
    private String stockName;
}
