package com.scyborsa.api.utils;

/**
 * TradingView sinyal yardımcı metodları.
 * Sembol formatlama + sinyal dönüştürme.
 */
public final class TwUtils {

    /** BIST borsa kodu prefix'i (orn. "BIST:THYAO"). */
    public static final String BIST_PREFIX = "BIST:";

    // ==================== SİNYAL SABİTLERİ ====================

    /** Guclu alis sinyali metni. */
    public static final String SIGNAL_STRONG_BUY = "Güçlü Al";
    /** Alis sinyali metni. */
    public static final String SIGNAL_BUY = "Al";
    /** Notr sinyal metni. */
    public static final String SIGNAL_NEUTRAL = "Nötr";
    /** Satis sinyali metni. */
    public static final String SIGNAL_SELL = "Sat";
    /** Guclu satis sinyali metni. */
    public static final String SIGNAL_STRONG_SELL = "Güçlü Sat";

    // ==================== EŞİK DEĞERLERİ ====================

    /** Guclu alis sinyal esigi (Recommend.All &gt;= 0.5). */
    public static final double THRESHOLD_STRONG_BUY = 0.5;
    /** Alis sinyal esigi (Recommend.All &gt;= 0.1). */
    public static final double THRESHOLD_BUY = 0.1;
    /** Satis sinyal esigi (Recommend.All &lt;= -0.1). */
    public static final double THRESHOLD_SELL = -0.1;
    /** Guclu satis sinyal esigi (Recommend.All &lt;= -0.5). */
    public static final double THRESHOLD_STRONG_SELL = -0.5;

    private TwUtils() {
    }

    // ==================== SEMBOL FORMAT ====================

    /**
     * Hisse adini TradingView sembol formatina ("BIST:XXXX") donusturur.
     * Zaten "BIST:" prefix'i varsa aynen dondurur.
     *
     * @param stockName hisse adi (ornegin "THYAO")
     * @return TradingView formatinda sembol (ornegin "BIST:THYAO"), null/bos ise {@code null}
     */
    public static String formatSymbol(String stockName) {
        if (stockName == null || stockName.trim().isEmpty()) {
            return null;
        }
        String trimmed = stockName.trim().toUpperCase();
        if (trimmed.startsWith(BIST_PREFIX)) {
            return trimmed;
        }
        return BIST_PREFIX + trimmed;
    }

    /**
     * TradingView sembolunden ("BIST:XXXX") hisse adini cikarir.
     * "BIST:" prefix'i yoksa string'i aynen dondurur.
     *
     * @param symbol TradingView formatinda sembol (ornegin "BIST:THYAO")
     * @return hisse adi (ornegin "THYAO"), null/bos ise {@code null}
     */
    public static String extractStockName(String symbol) {
        if (symbol == null || symbol.trim().isEmpty()) {
            return null;
        }
        String trimmed = symbol.trim().toUpperCase();
        if (trimmed.startsWith(BIST_PREFIX)) {
            return trimmed.substring(BIST_PREFIX.length());
        }
        return trimmed;
    }

    // ==================== SİNYAL METODLARI ====================

    /**
     * Verilen orani (-1.0 ile 1.0 arasi) Turkce sinyal metnine donusturur.
     * Esik degerleri: &lt;=-0.4 Guclu Sat, &lt;=-0.1 Sat, &lt;=0.1 Notr, &lt;=0.4 Al, &gt;0.4 Guclu Al.
     *
     * @param ratio sinyal orani
     * @return Turkce sinyal metni
     */
    public static String mapRatioToSignal(double ratio) {
        if (ratio <= -0.4) return SIGNAL_STRONG_SELL;
        else if (ratio <= -0.1) return SIGNAL_SELL;
        else if (ratio <= 0.1) return SIGNAL_NEUTRAL;
        else if (ratio <= 0.4) return SIGNAL_BUY;
        else return SIGNAL_STRONG_BUY;
    }

    /**
     * TradingView'in "Recommend.All" degerini Turkce sinyal metnine donusturur.
     * Esik degerleri: &gt;=0.5 Guclu Al, &gt;=0.1 Al, &lt;=-0.5 Guclu Sat, &lt;=-0.1 Sat, diger Notr.
     *
     * @param recommendAll TradingView Recommend.All degeri (null olabilir)
     * @return Turkce sinyal metni
     */
    public static String getSinyalFromJson(Double recommendAll) {
        if (recommendAll == null) return SIGNAL_NEUTRAL;
        if (recommendAll >= THRESHOLD_STRONG_BUY) return SIGNAL_STRONG_BUY;
        else if (recommendAll >= THRESHOLD_BUY) return SIGNAL_BUY;
        else if (recommendAll <= THRESHOLD_STRONG_SELL) return SIGNAL_STRONG_SELL;
        else if (recommendAll <= THRESHOLD_SELL) return SIGNAL_SELL;
        else return SIGNAL_NEUTRAL;
    }

    // ==================== YARDIMCI KONTROLLER ====================

    /**
     * Verilen sinyal metninin alis sinyali (Al veya Guclu Al) olup olmadigini kontrol eder.
     *
     * @param sinyal sinyal metni
     * @return alis sinyaliyse {@code true}
     */
    public static boolean isAlSinyali(String sinyal) {
        return SIGNAL_BUY.equals(sinyal) || SIGNAL_STRONG_BUY.equals(sinyal);
    }

    /**
     * Verilen sinyal metninin satis sinyali (Sat veya Guclu Sat) olup olmadigini kontrol eder.
     *
     * @param sinyal sinyal metni
     * @return satis sinyaliyse {@code true}
     */
    public static boolean isSatSinyali(String sinyal) {
        return SIGNAL_SELL.equals(sinyal) || SIGNAL_STRONG_SELL.equals(sinyal);
    }

    /**
     * Verilen sinyal metninin notr olup olmadigini kontrol eder.
     *
     * @param sinyal sinyal metni
     * @return notrse {@code true}
     */
    public static boolean isNotr(String sinyal) {
        return SIGNAL_NEUTRAL.equals(sinyal);
    }

    /**
     * Verilen sinyal metninin guclu sinyal (Guclu Al veya Guclu Sat) olup olmadigini kontrol eder.
     *
     * @param sinyal sinyal metni
     * @return guclu sinyalse {@code true}
     */
    public static boolean isGucluSinyal(String sinyal) {
        return SIGNAL_STRONG_BUY.equals(sinyal) || SIGNAL_STRONG_SELL.equals(sinyal);
    }

    /**
     * Recommend.All sayisal degerinin alis sinyali esigini asip asmadigini kontrol eder.
     *
     * @param recommendAll TradingView Recommend.All degeri (null olabilir)
     * @return alis sinyali esigi asilmissa {@code true}
     */
    public static boolean isAlSinyali(Double recommendAll) {
        return recommendAll != null && recommendAll >= THRESHOLD_BUY;
    }

    /**
     * Recommend.All sayisal degerinin satis sinyali esiginin altinda olup olmadigini kontrol eder.
     *
     * @param recommendAll TradingView Recommend.All degeri (null olabilir)
     * @return satis sinyali esigi altindaysa {@code true}
     */
    public static boolean isSatSinyali(Double recommendAll) {
        return recommendAll != null && recommendAll <= THRESHOLD_SELL;
    }

    /**
     * Ingilizce sinyal metnini Turkce sinyal metnine cevirir.
     * Desteklenen degerler: STRONG_BUY, BUY, NEUTRAL, SELL, STRONG_SELL.
     * Taninmayan degerler icin Notr dondurur.
     *
     * @param englishSignal Ingilizce sinyal metni (null olabilir)
     * @return Turkce sinyal metni
     */
    public static String translateSignal(String englishSignal) {
        if (englishSignal == null) return SIGNAL_NEUTRAL;
        return switch (englishSignal.toUpperCase()) {
            case "STRONG_BUY" -> SIGNAL_STRONG_BUY;
            case "BUY" -> SIGNAL_BUY;
            case "NEUTRAL" -> SIGNAL_NEUTRAL;
            case "SELL" -> SIGNAL_SELL;
            case "STRONG_SELL" -> SIGNAL_STRONG_SELL;
            default -> SIGNAL_NEUTRAL;
        };
    }

    /**
     * Sinyal metnine karsilik gelen emoji'yi dondurur.
     * Guclu Al: yesil cift daire, Al: yesil daire, Notr: beyaz daire,
     * Sat: kirmizi daire, Guclu Sat: kirmizi cift daire.
     *
     * @param sinyal sinyal metni (null olabilir)
     * @return sinyal emoji'si
     */
    public static String getSinyalEmoji(String sinyal) {
        if (sinyal == null) return "⚪";
        return switch (sinyal) {
            case SIGNAL_STRONG_BUY -> "🟢🟢";
            case SIGNAL_BUY -> "🟢";
            case SIGNAL_NEUTRAL -> "⚪";
            case SIGNAL_SELL -> "🔴";
            case SIGNAL_STRONG_SELL -> "🔴🔴";
            default -> "⚪";
        };
    }

    /**
     * Sinyal metnini sayisal skora donusturur.
     * Guclu Al: +2, Al: +1, Notr: 0, Sat: -1, Guclu Sat: -2.
     *
     * @param sinyal sinyal metni (null olabilir)
     * @return sinyal skoru (-2 ile +2 arasi)
     */
    public static int getSinyalScore(String sinyal) {
        if (sinyal == null) return 0;
        return switch (sinyal) {
            case SIGNAL_STRONG_BUY -> 2;
            case SIGNAL_BUY -> 1;
            case SIGNAL_NEUTRAL -> 0;
            case SIGNAL_SELL -> -1;
            case SIGNAL_STRONG_SELL -> -2;
            default -> 0;
        };
    }
}
