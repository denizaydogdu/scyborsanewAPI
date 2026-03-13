package com.scyborsa.api.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Tarama türlerini tanımlayan enum.
 *
 * <p>Her tür, TradingView Screener API'sine gönderilecek farklı bir tarama
 * stratejisi grubunu temsil eder. Scan body JSON dosyaları
 * {@code resources/screener-bodies/<resourcePath>/} dizininde bulunur.</p>
 *
 * @see com.scyborsa.api.config.ScreenerScanBodyRegistry
 */
@Getter
@RequiredArgsConstructor
public enum ScreenerTypeEnum {

    /** Mix tarama stratejileri. */
    MIX_TARAMA("MIX_TARAMA", "MIX TARAMASI", "mix-tarama"),

    /** SCY ozel tarama stratejileri. */
    ORTAMI_TARA("ORTAMI_TARA", "SCY TARAMALARI", "ortami-tara"),

    /** Seyda Hoca karma taramalari. */
    GET_SEYDA_TARAMALAR("GET_SEYDA_TARAMALAR", "KARMA TARAMALAR", "seyda-taramalar"),

    /** Caglar Hoca tarama stratejisi. */
    CAGLAR_TARAMA("CAGLAR_TARAMA", "CAGLAR HOCA TARAMASI", "caglar-tarama"),

    /** MACD bazli tarama stratejileri. */
    MACD_TARAMALAR("MACD_TARAMALAR", "MACD TARAMALARI", "macd-taramalar"),

    /** Hacim arayisi tarama stratejileri. */
    HACIM_ARAYISI("HACIM_ARAYISI", "HACIM ARAYISI TARAMALARI", "hacim-arayisi"),

    /** Uzman tarama stratejileri. */
    UZMAN_TARAMALAR("UZMAN_TARAMALAR", "UZMAN TARAMALARI", "uzman-taramalar"),

    /** Piyasa ozeti taramalari (Telegram job icin). */
    MARKET_SUMMARY("MARKET_SUMMARY", "PİYASA ÖZETİ", "market-summary");

    /** Tarama kodunu temsil eden tekil tanımlayıcı (ör. "MIX_TARAMA"). */
    private final String code;

    /** Kullanıcı arayüzünde ve loglarda gösterilecek açıklayıcı ad. */
    private final String displayName;

    /** {@code resources/screener-bodies/} altındaki dizin adı. */
    private final String resourcePath;
}
