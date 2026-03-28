package com.scyborsa.api.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Hazir tarama stratejilerini tanimlayan enum.
 *
 * <p>Her strateji bir teknik analiz kosulunu temsil eder.
 * Kategoriler: MOMENTUM, TREND, HACIM, VOLATILITE, KOMPOZIT.</p>
 *
 * @see com.scyborsa.api.config.PresetStrategyRegistry
 * @see com.scyborsa.api.service.market.HazirTaramalarService
 */
@Getter
@RequiredArgsConstructor
public enum PresetStrategyEnum {

    // ==================== MOMENTUM (8) ====================

    /** RSI 30 altinda — asiri satim bolgesi. */
    RSI_OVERSOLD("rsi_oversold", "RSI Aşırı Satım", "RSI 30 altında olan hisseler", "MOMENTUM"),

    /** RSI 70 ustunde — asiri alim bolgesi. */
    RSI_OVERBOUGHT("rsi_overbought", "RSI Aşırı Alım", "RSI 70 üstünde olan hisseler", "MOMENTUM"),

    /** Stochastic K 20 altinda — asiri satim. */
    STOCH_OVERSOLD("stoch_oversold", "Stokastik Aşırı Satım", "Stochastic K 20 altında olan hisseler", "MOMENTUM"),

    /** Stochastic K 80 ustunde — asiri alim. */
    STOCH_OVERBOUGHT("stoch_overbought", "Stokastik Aşırı Alım", "Stochastic K 80 üstünde olan hisseler", "MOMENTUM"),

    /** Momentum pozitif. */
    MOMENTUM_POSITIVE("momentum_positive", "Pozitif Momentum", "Momentum göstergesi pozitif olan hisseler", "MOMENTUM"),

    /** Williams %R -80 altinda — asiri satim. */
    WILLIAMS_R_OVERSOLD("williams_r_oversold", "Williams %R Aşırı Satım", "Williams %R -80 altında olan hisseler", "MOMENTUM"),

    /** Ultimate Oscillator 50 ustunde — guclu. */
    ULTIMATE_OSCILLATOR_STRONG("ultimate_oscillator_strong", "Güçlü Ultimate Oscillator", "Ultimate Oscillator 50 üstünde olan hisseler", "MOMENTUM"),

    /** CCI 100 ustunde — yukselis bolgesi. */
    CCI_BULLISH_ZONE("cci_bullish_zone", "CCI Yükseliş Bölgesi", "CCI 100 üstünde olan hisseler", "MOMENTUM"),

    // ==================== TREND (7) ====================

    /** MACD sinyal cizgisinin ustunde ve pozitif. */
    MACD_POSITIVE("macd_positive", "MACD Pozitif Kesişim", "MACD sinyal çizgisinin üstünde ve pozitif olan hisseler", "TREND"),

    /** Fiyat EMA20 ustunde, EMA20 > EMA50 > EMA200. */
    EMA_TREND_UP("ema_trend_up", "EMA Yükseliş Trendi", "Fiyat > EMA20 > EMA50 > EMA200 olan hisseler", "TREND"),

    /** ADX 25 ustunde ve +DI > -DI. */
    ADX_STRONG_TREND("adx_strong_trend", "Güçlü ADX Trendi", "ADX 25 üstünde ve +DI > -DI olan hisseler", "TREND"),

    /** SMA50 > SMA200 — altin capraz. */
    SMA_ABOVE_ORDER("sma_above_order", "SMA Sıralaması (SMA50 > SMA200)", "SMA50 > SMA200 olan hisseler", "TREND"),

    /** SMA50 < SMA200 — olum caprazi. */
    SMA_BELOW_ORDER("sma_below_order", "SMA Ölüm Çaprazı", "SMA50 < SMA200 olan hisseler (ölüm çaprazı)", "TREND"),

    /** Fiyat Parabolic SAR ustunde. */
    PARABOLIC_SAR_ABOVE("parabolic_sar_above", "Parabolic SAR Üstünde", "Fiyat Parabolic SAR üstünde olan hisseler", "TREND"),

    /** Fiyat Ichimoku bulutu ustunde. */
    ICHIMOKU_ABOVE_CLOUD("ichimoku_above_cloud", "Ichimoku Bulut Üzerinde", "Fiyat Ichimoku bulutu üzerinde olan hisseler", "TREND"),

    // ==================== HACIM (3) ====================

    /** Goreceli hacim 2x ustunde — hacim patlamasi. */
    VOLUME_SPIKE("volume_spike", "Hacim Patlaması", "Göreceli hacim 2x üstünde olan hisseler", "HACIM"),

    /** Goreceli hacim 3x+ ve degisim %2+ — kirilim. */
    HIGH_VOLUME_BREAKOUT("high_volume_breakout", "Yüksek Hacimli Kırılım", "Göreceli hacim 3x üstünde ve %2+ yükselen hisseler", "HACIM"),

    /** Money Flow 80 ustunde — guclu para girisi. */
    STRONG_MONEYFLOW("strong_moneyflow", "Güçlü Para Girişi", "Money Flow 80 üstünde olan hisseler", "HACIM"),

    // ==================== VOLATILITE (2) ====================

    /** Bollinger Band sikismasi — dusuk volatilite. */
    BB_SQUEEZE("bb_squeeze", "Bollinger Band Daralması", "Bollinger Band genişliği %4 altında olan hisseler", "VOLATILITE"),

    /** Dusuk volatilite — gunluk volatilite %2 altinda. */
    LOW_VOLATILITY("low_volatility", "Düşük Volatilite", "Günlük volatilite %2 altında olan hisseler", "VOLATILITE"),

    // ==================== KOMPOZIT (8) ====================

    /** Fiyat SMA50 ustunde. */
    PRICE_ABOVE_SMA50("price_above_sma50", "SMA50 Üstünde", "Fiyat SMA50 üstünde olan hisseler", "KOMPOZIT"),

    /** RSI dusuk ama fiyat yükseliyor — dipten donus. */
    OVERSOLD_BOUNCE("oversold_bounce", "Aşırı Satımdan Dönüş", "RSI 35 altında ve fiyat yükselen hisseler", "KOMPOZIT"),

    /** ROC 5 ustunde — guclu fiyat degisimi. */
    HIGH_ROC("high_roc", "Yüksek ROC", "Rate of Change %5 üstünde olan hisseler", "KOMPOZIT"),

    /** DMI yukselis — +DI > -DI ve ADX 20+. */
    DMI_BULLISH("dmi_bullish", "DMI Yükseliş", "+DI > -DI ve ADX 20 üstünde olan hisseler", "KOMPOZIT"),

    /** Momentum ve ROC pozitif — cift onay. */
    TRIX_POSITIVE("trix_positive", "Momentum & ROC Pozitif", "Momentum ve ROC göstergeleri pozitif olan hisseler", "KOMPOZIT"),

    /** Yon gostergesi yukselis — +DI > -DI. */
    VORTEX_BULLISH("vortex_bullish", "+DI > -DI Yön Göstergesi", "+DI > -DI olan hisseler (yön göstergesi)", "KOMPOZIT"),

    /** Aroon yukselis trendi — Up 70+, Down 30-. */
    AROON_UP_TREND("aroon_up_trend", "Aroon Yükseliş", "Aroon Up 70 üstünde ve Down 30 altında olan hisseler", "KOMPOZIT"),

    /** CCI 200 ustunde — cok guclu yukselis. */
    COMMODITY_CHANNEL_STRONG("commodity_channel_strong", "Güçlü CCI", "CCI 200 üstünde olan hisseler", "KOMPOZIT");

    /** Strateji kodu (API'de kullanilan tekil tanimlayici). */
    private final String code;

    /** Kullanici arayuzunde gosterilecek Turkce ad. */
    private final String displayName;

    /** Strateji aciklamasi. */
    private final String description;

    /** Strateji kategorisi (MOMENTUM, TREND, HACIM, VOLATILITE, KOMPOZIT). */
    private final String category;

    /**
     * Kod degerine gore enum sabitini bulur.
     *
     * @param code aranacak strateji kodu
     * @return eslesen enum sabiti; bulunamazsa {@code null}
     */
    public static PresetStrategyEnum fromCode(String code) {
        if (code == null) return null;
        for (PresetStrategyEnum strategy : values()) {
            if (strategy.code.equals(code)) {
                return strategy;
            }
        }
        return null;
    }
}
