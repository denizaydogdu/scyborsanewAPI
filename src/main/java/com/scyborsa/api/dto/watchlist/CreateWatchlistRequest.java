package com.scyborsa.api.dto.watchlist;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Takip listesi olusturma istegi DTO'su.
 *
 * <p>Kullanicinin yeni bir takip listesi olustururken gonderdigi verileri tasir.</p>
 *
 * @see com.scyborsa.api.model.Watchlist
 */
@Data
public class CreateWatchlistRequest {

    /** Liste adi. Zorunlu, maks 50 karakter. */
    @NotBlank
    @Size(max = 50)
    private String name;

    /** Liste aciklamasi (opsiyonel, maks 200 karakter). */
    @Size(max = 200)
    private String description;
}
