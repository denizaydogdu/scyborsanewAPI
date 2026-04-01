package com.scyborsa.api.enums;

/**
 * Yatirim vadesi enum sinifi.
 *
 * <p>Takip edilen hisse onerilerinin vade turunu belirler.
 * Kisa, orta ve uzun vade olarak uc kategoriden olusur.</p>
 *
 * @see com.scyborsa.api.model.TakipHissesi
 */
public enum YatirimVadesi {

    /** Kisa vadeli yatirim onerisi (1-3 ay). */
    KISA_VADE,

    /** Orta vadeli yatirim onerisi (3-12 ay). */
    ORTA_VADE,

    /** Uzun vadeli yatirim onerisi (12+ ay). */
    UZUN_VADE
}
