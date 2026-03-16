package com.scyborsa.api.dto.ai;

import com.scyborsa.api.dto.screener.TvScreenerResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * TradingView Teknik Veri DTO.
 *
 * <p>ai-tek-hisse-scan.json scan body'sinden gelen 86 alani
 * parse edip yapilandirilmis sekilde sunar.</p>
 *
 * <h3>Veri Kategorileri:</h3>
 * <ul>
 *   <li>Temel Bilgi: ticker, close, type, currency</li>
 *   <li>Fiyat ve Degerleme: P/E, EPS, dividends, EBITDA, market_cap</li>
 *   <li>Sektor: sector, sector.tr, market</li>
 *   <li>Ratings: AnalystRating, TechRating_1D, MARating_1D</li>
 *   <li>Moving Averages: SMA50, EMA50, HullMA9</li>
 *   <li>Bollinger Bands: BB.upper, BB.basis, BB.lower, BBPower</li>
 *   <li>Aroon: Aroon.Up, Aroon.Down</li>
 *   <li>Pivot Points: Camarilla R1-R3, S1-S3, Middle</li>
 *   <li>Momentum/Oscillators: RSI, MACD, Stochastic, ChaikinMoneyFlow, Mom, ROC</li>
 *   <li>ADX: ADX+DI, ADX-DI</li>
 *   <li>Volume: VWMA</li>
 *   <li>27 Mum Formasyonu: Doji, Engulfing, Hammer, MorningStar, vb.</li>
 * </ul>
 *
 * @author scyborsa
 * @version 1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Slf4j
public class TechnicalDataDTO {

    // ==================== TEMEL BILGILER (Index 0-12) ====================

    /** Hisse kodu (Index 0: ticker-view). */
    private String stockCode;

    /** Kapanis fiyati (Index 1: close). */
    private Double close;

    /** Tip (Index 2: type - stock/etf). */
    private String type;

    /** Para birimi (Index 8: currency). */
    private String currency;

    /** Degisim (Index 9: change). */
    private Double change;

    /** Hacim (Index 10: volume). */
    private Long volume;

    /** Goreceli hacim (Index 11: relative_volume_10d_calc). */
    private Double relativeVolume;

    /** Piyasa degeri (Index 12: market_cap_basic). */
    private Long marketCap;

    // ==================== DEGERLEME (Index 14-17) ====================

    /** F/K orani (Index 14: price_earnings_ttm). */
    private Double peRatio;

    /** Hisse basi kazanc (Index 15: earnings_per_share_diluted_ttm). */
    private Double eps;

    /** EPS yillik buyume (Index 16: earnings_per_share_diluted_yoy_growth_ttm). */
    private Double epsYoyGrowth;

    /** Temettu verimi (Index 17: dividends_yield_current). */
    private Double dividendYield;

    // ==================== SEKTOR (Index 18-20) ====================

    /** Sektor Turkce (Index 18: sector.tr). */
    private String sectorTr;

    /** Market (Index 19: market). */
    private String market;

    /** Sektor Ingilizce (Index 20: sector). */
    private String sector;

    // ==================== RATINGS (Index 21-22, 46-47, 53-54) ====================

    /** Analist Rating (Index 21: AnalystRating). */
    private Double analystRating;

    /** Analist Rating Turkce (Index 22: AnalystRating.tr). */
    private String analystRatingTr;

    /** MA Rating 1D (Index 46: MARating_1D). */
    private Double maRating;

    /** MA Rating Turkce (Index 47: MARating_1D.tr). */
    private String maRatingTr;

    /** Tech Rating 1D (Index 53: TechRating_1D). */
    private Double techRating;

    /** Tech Rating Turkce (Index 54: TechRating_1D.tr). */
    private String techRatingTr;

    // ==================== AROON (Index 23-24) ====================

    /** Aroon Up (Index 23). */
    private Double aroonUp;

    /** Aroon Down (Index 24). */
    private Double aroonDown;

    // ==================== HAREKETLI ORTALAMALAR (Index 25, 42, 55) ====================

    /** SMA 50 (Index 25). */
    private Double sma50;

    /** Hull MA 9 (Index 42). */
    private Double hullMa9;

    /** EMA 50 (Index 55). */
    private Double ema50;

    // ==================== BOLLINGER BANDS (Index 26-29) ====================

    /** BB Power (Index 26). */
    private Double bbPower;

    /** BB Upper (Index 27). */
    private Double bbUpper;

    /** BB Basis (Index 28). */
    private Double bbBasis;

    /** BB Lower (Index 29). */
    private Double bbLower;

    // ==================== PIVOT POINTS - CAMARILLA (Index 30-36) ====================

    /** Pivot Middle (Index 30). */
    private Double pivotMiddle;

    /** Pivot R1 (Index 31). */
    private Double pivotR1;

    /** Pivot R2 (Index 32). */
    private Double pivotR2;

    /** Pivot R3 (Index 33). */
    private Double pivotR3;

    /** Pivot S1 (Index 34). */
    private Double pivotS1;

    /** Pivot S2 (Index 35). */
    private Double pivotS2;

    /** Pivot S3 (Index 36). */
    private Double pivotS3;

    // ==================== OSILATORLER (Index 37-45) ====================

    /** Chaikin Money Flow (Index 37). */
    private Double chaikinMoneyFlow;

    /** ROC (Index 38). */
    private Double roc;

    /** Stochastic K (Index 39). */
    private Double stochK;

    /** Stochastic D (Index 40). */
    private Double stochD;

    /** Momentum (Index 41). */
    private Double mom;

    /** RSI (Index 43). */
    private Double rsi;

    /** MACD (Index 44). */
    private Double macdValue;

    /** MACD Signal (Index 45). */
    private Double macdSignal;

    // ==================== DIGER (Index 48-52) ====================

    /** Money Flow (Index 48). */
    private Double moneyFlow;

    /** EBITDA (Index 49). */
    private Long ebitda;

    /** Net Income (Index 50). */
    private Long netIncome;

    /** Total Revenue (Index 51). */
    private Long totalRevenue;

    /** Parabolic SAR (Index 52). */
    private Double pSar;

    // ==================== ADX (Index 56-57) ====================

    /** ADX +DI (Index 56). */
    private Double adxPlusDi;

    /** ADX -DI (Index 57). */
    private Double adxMinusDi;

    /** VWMA (Index 85). */
    private Double vwma;

    // ==================== MUM FORMASYONLARI (Index 58-84) ====================

    /** Tespit edilen bullish (yukselis) formasyonlari. */
    @Builder.Default
    private List<String> bullishPatterns = new ArrayList<>();

    /** Tespit edilen bearish (dusus) formasyonlari. */
    @Builder.Default
    private List<String> bearishPatterns = new ArrayList<>();

    /** Tespit edilen notr formasyonlar. */
    @Builder.Default
    private List<String> neutralPatterns = new ArrayList<>();

    // ==================== FACTORY METHOD ====================

    /**
     * TvScreenerResponse'dan TechnicalDataDTO olusturur.
     *
     * <p>Scan body'deki 86 kolonu index bazli parse ederek
     * yapilandirilmis DTO'ya donusturur.</p>
     *
     * @param response TradingView API yaniti
     * @return TechnicalDataDTO veya null (yetersiz veri durumunda)
     */
    public static TechnicalDataDTO fromTvResponse(TvScreenerResponse response) {
        if (response == null || response.getData() == null || response.getData().isEmpty()) {
            return null;
        }

        TvScreenerResponse.DataItem data = response.getData().get(0);
        if (data == null || data.getD() == null || data.getD().size() < 86) {
            log.warn("Yetersiz veri: {} alan", data != null && data.getD() != null ? data.getD().size() : 0);
            return null;
        }

        List<Object> d = data.getD();

        TechnicalDataDTOBuilder builder = TechnicalDataDTO.builder();

        try {
            // Temel bilgiler
            builder.stockCode(getString(d, 0));
            builder.close(getDouble(d, 1));
            builder.type(getString(d, 2));
            builder.currency(getString(d, 8));
            builder.change(getDouble(d, 9));
            builder.volume(getLong(d, 10));
            builder.relativeVolume(getDouble(d, 11));
            builder.marketCap(getLong(d, 12));

            // Degerleme
            builder.peRatio(getDouble(d, 14));
            builder.eps(getDouble(d, 15));
            builder.epsYoyGrowth(getDouble(d, 16));
            builder.dividendYield(getDouble(d, 17));

            // Sektor
            builder.sectorTr(getString(d, 18));
            builder.market(getString(d, 19));
            builder.sector(getString(d, 20));

            // Ratings
            builder.analystRating(getDouble(d, 21));
            builder.analystRatingTr(getString(d, 22));
            builder.maRating(getDouble(d, 46));
            builder.maRatingTr(getString(d, 47));
            builder.techRating(getDouble(d, 53));
            builder.techRatingTr(getString(d, 54));

            // Aroon
            builder.aroonUp(getDouble(d, 23));
            builder.aroonDown(getDouble(d, 24));

            // Moving Averages
            builder.sma50(getDouble(d, 25));
            builder.hullMa9(getDouble(d, 42));
            builder.ema50(getDouble(d, 55));

            // Bollinger Bands
            builder.bbPower(getDouble(d, 26));
            builder.bbUpper(getDouble(d, 27));
            builder.bbBasis(getDouble(d, 28));
            builder.bbLower(getDouble(d, 29));

            // Pivot Points
            builder.pivotMiddle(getDouble(d, 30));
            builder.pivotR1(getDouble(d, 31));
            builder.pivotR2(getDouble(d, 32));
            builder.pivotR3(getDouble(d, 33));
            builder.pivotS1(getDouble(d, 34));
            builder.pivotS2(getDouble(d, 35));
            builder.pivotS3(getDouble(d, 36));

            // Oscillators
            builder.chaikinMoneyFlow(getDouble(d, 37));
            builder.roc(getDouble(d, 38));
            builder.stochK(getDouble(d, 39));
            builder.stochD(getDouble(d, 40));
            builder.mom(getDouble(d, 41));
            builder.rsi(getDouble(d, 43));
            builder.macdValue(getDouble(d, 44));
            builder.macdSignal(getDouble(d, 45));

            // Other
            builder.moneyFlow(getDouble(d, 48));
            builder.ebitda(getLong(d, 49));
            builder.netIncome(getLong(d, 50));
            builder.totalRevenue(getLong(d, 51));
            builder.pSar(getDouble(d, 52));

            // ADX
            builder.adxPlusDi(getDouble(d, 56));
            builder.adxMinusDi(getDouble(d, 57));

            // VWMA
            builder.vwma(getDouble(d, 85));

            // Mum formasyonlari parse et
            List<String> bullish = new ArrayList<>();
            List<String> bearish = new ArrayList<>();
            List<String> neutral = new ArrayList<>();
            parseCandlePatterns(d, bullish, bearish, neutral);

            builder.bullishPatterns(bullish);
            builder.bearishPatterns(bearish);
            builder.neutralPatterns(neutral);

        } catch (Exception e) {
            log.error("TechnicalDataDTO parse hatasi: {}", e.getMessage());
            return null;
        }

        return builder.build();
    }

    // ==================== PARSE HELPERS ====================

    /**
     * Belirtilen indexteki degeri String olarak okur.
     *
     * @param d veri listesi
     * @param index okunacak index
     * @return String deger veya null
     */
    private static String getString(List<Object> d, int index) {
        if (index >= d.size() || d.get(index) == null) return null;
        return d.get(index).toString();
    }

    /**
     * Belirtilen indexteki degeri Double olarak okur.
     *
     * @param d veri listesi
     * @param index okunacak index
     * @return Double deger veya null
     */
    private static Double getDouble(List<Object> d, int index) {
        if (index >= d.size() || d.get(index) == null) return null;
        Object val = d.get(index);
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        }
        try {
            return Double.parseDouble(val.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Belirtilen indexteki degeri Long olarak okur.
     *
     * @param d veri listesi
     * @param index okunacak index
     * @return Long deger veya null
     */
    private static Long getLong(List<Object> d, int index) {
        if (index >= d.size() || d.get(index) == null) return null;
        Object val = d.get(index);
        if (val instanceof Number) {
            return ((Number) val).longValue();
        }
        try {
            return Long.parseLong(val.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Mum formasyonlarini parse eder (Index 58-84).
     *
     * <p>TradingView mum pattern degerleri:</p>
     * <ul>
     *   <li>0 = Formasyon yok</li>
     *   <li>Pozitif = Bullish formasyon</li>
     *   <li>Negatif = Bearish formasyon (bazi pattern'ler icin)</li>
     * </ul>
     *
     * @param d veri listesi
     * @param bullish yukselis formasyonlari listesi
     * @param bearish dusus formasyonlari listesi
     * @param neutral notr formasyonlar listesi
     */
    private static void parseCandlePatterns(List<Object> d,
                                             List<String> bullish,
                                             List<String> bearish,
                                             List<String> neutral) {
        // Pattern name ve index mapping
        String[][] patterns = {
            {"58", "3 Black Crows", "bearish"},
            {"59", "3 White Soldiers", "bullish"},
            {"60", "Abandoned Baby (Bearish)", "bearish"},
            {"61", "Abandoned Baby (Bullish)", "bullish"},
            {"62", "Doji", "neutral"},
            {"63", "Dragonfly Doji", "bullish"},
            {"64", "Gravestone Doji", "bearish"},
            {"65", "Engulfing (Bearish)", "bearish"},
            {"66", "Engulfing (Bullish)", "bullish"},
            {"67", "Evening Star", "bearish"},
            {"68", "Hammer", "bullish"},
            {"69", "Hanging Man", "bearish"},
            {"70", "Harami (Bearish)", "bearish"},
            {"71", "Harami (Bullish)", "bullish"},
            {"72", "Inverted Hammer", "bullish"},
            {"73", "Kicking (Bearish)", "bearish"},
            {"74", "Kicking (Bullish)", "bullish"},
            {"75", "Long Shadow Lower", "bullish"},
            {"76", "Long Shadow Upper", "bearish"},
            {"77", "Marubozu Black", "bearish"},
            {"78", "Marubozu White", "bullish"},
            {"79", "Morning Star", "bullish"},
            {"80", "Shooting Star", "bearish"},
            {"81", "Spinning Top Black", "neutral"},
            {"82", "Spinning Top White", "neutral"},
            {"83", "TriStar (Bearish)", "bearish"},
            {"84", "TriStar (Bullish)", "bullish"}
        };

        for (String[] pattern : patterns) {
            int index = Integer.parseInt(pattern[0]);
            String name = pattern[1];
            String type = pattern[2];

            Double value = getDouble(d, index);
            if (value != null && Double.compare(value, 0.0) != 0) {
                switch (type) {
                    case "bullish" -> bullish.add(name);
                    case "bearish" -> bearish.add(name);
                    case "neutral" -> neutral.add(name);
                }
            }
        }
    }

    // ==================== PROMPT OLUSTURMA ====================

    /**
     * AI prompt icin teknik analiz bolumu olusturur.
     *
     * <p>RSI, MACD, Stochastic, ADX, Aroon, Bollinger Bands
     * ve rating bilgilerini iceren yapilandirilmis metin uretir.</p>
     *
     * @return teknik analiz prompt bolumu
     */
    public String toTechnicalPromptSection() {
        StringBuilder sb = new StringBuilder();

        sb.append("\nTEKNIK ANALIZ:\n");

        // RSI
        if (rsi != null) {
            sb.append("- RSI(14): ").append(String.format("%.1f", rsi));
            sb.append(" (").append(getRsiSignal()).append(")\n");
        }

        // MACD
        if (macdValue != null && macdSignal != null) {
            sb.append("- MACD: ").append(String.format("%.2f", macdValue));
            sb.append(" (Sinyal: ").append(String.format("%.2f", macdSignal));
            sb.append(", ").append(getMacdSignalText()).append(")\n");
        }

        // Stochastic
        if (stochK != null && stochD != null) {
            sb.append("- Stochastic K/D: ")
              .append(String.format("%.1f", stochK)).append(" / ")
              .append(String.format("%.1f", stochD)).append("\n");
        }

        // ADX
        if (adxPlusDi != null && adxMinusDi != null) {
            sb.append("- ADX: +DI=").append(String.format("%.1f", adxPlusDi));
            sb.append(", -DI=").append(String.format("%.1f", adxMinusDi));
            sb.append(" (").append(getAdxSignal()).append(")\n");
        }

        // Aroon
        if (aroonUp != null && aroonDown != null) {
            sb.append("- Aroon: Up=").append(String.format("%.0f", aroonUp));
            sb.append(", Down=").append(String.format("%.0f", aroonDown));
            sb.append(" (").append(getAroonSignal()).append(")\n");
        }

        // Bollinger Bands
        if (bbUpper != null && bbBasis != null && bbLower != null && close != null) {
            sb.append("- Bollinger: ").append(getBollingerPosition()).append("\n");
        }

        // Technical Rating
        if (techRatingTr != null) {
            sb.append("- Teknik Sinyal: ").append(techRatingTr).append("\n");
        }

        // MA Rating
        if (maRatingTr != null) {
            sb.append("- MA Sinyal: ").append(maRatingTr).append("\n");
        }

        return sb.toString();
    }

    /**
     * AI prompt icin mum formasyonlari bolumu olusturur.
     *
     * <p>Tespit edilen bullish, bearish ve notr formasyonlari listeler.</p>
     *
     * @return mum formasyonlari prompt bolumu veya bos string
     */
    public String toCandlePatternPromptSection() {
        if (bullishPatterns.isEmpty() && bearishPatterns.isEmpty() && neutralPatterns.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\nMUM FORMASYONLARI:\n");

        if (!bullishPatterns.isEmpty()) {
            for (String p : bullishPatterns) {
                sb.append("  [+] ").append(p).append(" (Yukselis)\n");
            }
        }

        if (!bearishPatterns.isEmpty()) {
            for (String p : bearishPatterns) {
                sb.append("  [-] ").append(p).append(" (Dusus)\n");
            }
        }

        if (!neutralPatterns.isEmpty()) {
            for (String p : neutralPatterns) {
                sb.append("  [~] ").append(p).append(" (Kararsizlik)\n");
            }
        }

        return sb.toString();
    }

    /**
     * AI prompt icin pivot point bolumu olusturur.
     *
     * <p>Camarilla pivot destek/direnc seviyelerini listeler.</p>
     *
     * @return pivot point prompt bolumu veya bos string
     */
    public String toPivotPromptSection() {
        if (pivotR3 == null || pivotS3 == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\nDESTEK/DIRENC (Camarilla):\n");
        sb.append("- Direnc R3: ").append(String.format("%.2f", pivotR3)).append(" TL\n");
        if (pivotR2 != null) sb.append("- Direnc R2: ").append(String.format("%.2f", pivotR2)).append(" TL\n");
        if (pivotR1 != null) sb.append("- Direnc R1: ").append(String.format("%.2f", pivotR1)).append(" TL\n");
        if (pivotMiddle != null) sb.append("- Pivot: ").append(String.format("%.2f", pivotMiddle)).append(" TL\n");
        if (pivotS1 != null) sb.append("- Destek S1: ").append(String.format("%.2f", pivotS1)).append(" TL\n");
        if (pivotS2 != null) sb.append("- Destek S2: ").append(String.format("%.2f", pivotS2)).append(" TL\n");
        sb.append("- Destek S3: ").append(String.format("%.2f", pivotS3)).append(" TL\n");

        return sb.toString();
    }

    /**
     * AI prompt icin temel analiz bolumu olusturur.
     *
     * <p>F/K, EPS, temettu verimi, sektor ve piyasa degeri bilgilerini icerir.</p>
     *
     * @return temel analiz prompt bolumu
     */
    public String toFundamentalPromptSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("\nTEMEL ANALIZ:\n");

        if (peRatio != null && peRatio > 0) {
            sb.append("- F/K (P/E): ").append(String.format("%.2f", peRatio)).append("\n");
        }

        if (eps != null) {
            sb.append("- Hisse Basi Kazanc: ").append(String.format("%.2f", eps)).append(" TL\n");
        }

        if (dividendYield != null && dividendYield > 0) {
            sb.append("- Temettu Verimi: %").append(String.format("%.2f", dividendYield)).append("\n");
        }

        if (sectorTr != null && !sectorTr.isEmpty()) {
            sb.append("- Sektor: ").append(sectorTr).append("\n");
        }

        if (marketCap != null && marketCap > 0) {
            sb.append("- Piyasa Degeri: ").append(formatMarketCap(marketCap)).append("\n");
        }

        if (volume != null && volume > 0) {
            sb.append("- Hacim: ").append(formatVolume(volume)).append("\n");
        }

        return sb.toString();
    }

    // ==================== SIGNAL HELPERS ====================

    /**
     * RSI sinyalini degerlendirir.
     *
     * @return RSI sinyal metni
     */
    private String getRsiSignal() {
        if (rsi == null) return "Bilinmiyor";
        if (rsi >= 70) return "Asiri Alim";
        if (rsi <= 30) return "Asiri Satim";
        if (rsi >= 50) return "Pozitif";
        return "Negatif";
    }

    /**
     * MACD sinyal metnini degerlendirir.
     *
     * @return MACD sinyal metni
     */
    private String getMacdSignalText() {
        if (macdValue == null || macdSignal == null) return "Bilinmiyor";
        int cmp = Double.compare(macdValue, macdSignal);
        if (cmp > 0) return "Pozitif";
        if (cmp == 0) return "Notr";
        return "Negatif";
    }

    /**
     * ADX trend yonunu degerlendirir.
     *
     * @return ADX sinyal metni
     */
    private String getAdxSignal() {
        if (adxPlusDi == null || adxMinusDi == null) return "Bilinmiyor";
        if (adxPlusDi > adxMinusDi) return "Yukselis Trendi";
        return "Dusus Trendi";
    }

    /**
     * Aroon sinyalini degerlendirir.
     *
     * @return Aroon sinyal metni
     */
    private String getAroonSignal() {
        if (aroonUp == null || aroonDown == null) return "Bilinmiyor";
        if (aroonUp > 70 && aroonDown < 30) return "Guclu Yukselis";
        if (aroonDown > 70 && aroonUp < 30) return "Guclu Dusus";
        if (aroonUp > aroonDown) return "Yukselis";
        return "Dusus";
    }

    /**
     * Kapanis fiyatinin Bollinger Band icerisindeki konumunu degerlendirir.
     *
     * @return Bollinger pozisyon metni
     */
    private String getBollingerPosition() {
        if (close == null || bbUpper == null || bbLower == null || bbBasis == null) {
            return "Bilinmiyor";
        }
        if (close >= bbUpper) return "Ust Bant Uzerinde (Asiri Alim)";
        if (close <= bbLower) return "Alt Bant Altinda (Asiri Satim)";
        if (close > bbBasis) return "Orta Bant Uzerinde (Pozitif)";
        return "Orta Bant Altinda (Negatif)";
    }

    /**
     * Piyasa degerini Turkce formatlar.
     *
     * @param cap piyasa degeri (TL)
     * @return formatlanmis piyasa degeri metni
     */
    private String formatMarketCap(Long cap) {
        if (cap == null) return "0";
        if (cap >= 1_000_000_000_000L) {
            return String.format("%.1f Trilyon TL", cap / 1_000_000_000_000.0);
        }
        if (cap >= 1_000_000_000L) {
            return String.format("%.1f Milyar TL", cap / 1_000_000_000.0);
        }
        if (cap >= 1_000_000L) {
            return String.format("%.1f Milyon TL", cap / 1_000_000.0);
        }
        return cap + " TL";
    }

    /**
     * Hacim degerini Turkce formatlar.
     *
     * @param vol hacim degeri
     * @return formatlanmis hacim metni
     */
    private String formatVolume(Long vol) {
        if (vol == null) return "0";
        if (vol >= 1_000_000_000L) {
            return String.format("%.2f Milyar", vol / 1_000_000_000.0);
        }
        if (vol >= 1_000_000L) {
            return String.format("%.2f Milyon", vol / 1_000_000.0);
        }
        if (vol >= 1_000L) {
            return String.format("%.2f Bin", vol / 1_000.0);
        }
        return vol + " lot";
    }

    /**
     * Tum bolumleri birlestirip tam AI prompt olusturur.
     *
     * @return teknik + mum formasyonu + pivot + temel analiz birlesmis prompt
     */
    public String toFullAiPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append(toTechnicalPromptSection());
        sb.append(toCandlePatternPromptSection());
        sb.append(toPivotPromptSection());
        sb.append(toFundamentalPromptSection());
        return sb.toString();
    }

    /**
     * Kisa ozet (debug icin).
     *
     * @return hisse kodu, RSI, MACD ve teknik sinyal bilgisi
     */
    @Override
    public String toString() {
        return String.format("TechnicalData[%s|RSI:%.1f|MACD:%.2f|Tech:%s]",
                stockCode,
                rsi != null ? rsi : 0.0,
                macdValue != null ? macdValue : 0.0,
                techRatingTr != null ? techRatingTr : "N/A");
    }
}
