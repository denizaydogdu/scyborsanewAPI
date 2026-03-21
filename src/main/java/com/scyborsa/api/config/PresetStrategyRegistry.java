package com.scyborsa.api.config;

import com.scyborsa.api.enums.PresetStrategyEnum;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Hazir tarama stratejilerinin filtre kosullarini tutan registry.
 *
 * <p>Her {@link PresetStrategyEnum} icin bir {@link Predicate} tanimi icerir.
 * Predicate, TradingView Scanner API'den donen kolon-deger map'ine uygulanir.
 * Tum kosullar null-safe'dir — gerekli deger null ise predicate {@code false} doner.</p>
 *
 * @see PresetStrategyEnum
 * @see com.scyborsa.api.service.market.HazirTaramalarService
 */
@Slf4j
@Component
public class PresetStrategyRegistry {

    /** Strateji → filtre predicate eslesmesi. */
    private final Map<PresetStrategyEnum, Predicate<Map<String, Object>>> predicates =
            new EnumMap<>(PresetStrategyEnum.class);

    /**
     * Uygulama baslangicindan tum strateji filtrelerini kaydeder.
     */
    @PostConstruct
    public void init() {
        // ==================== MOMENTUM ====================
        predicates.put(PresetStrategyEnum.RSI_OVERSOLD, row -> {
            Double rsi = getDouble(row, "RSI");
            return rsi != null && rsi < 30;
        });

        predicates.put(PresetStrategyEnum.RSI_OVERBOUGHT, row -> {
            Double rsi = getDouble(row, "RSI");
            return rsi != null && rsi > 70;
        });

        predicates.put(PresetStrategyEnum.STOCH_OVERSOLD, row -> {
            Double stochK = getDouble(row, "Stoch.K");
            return stochK != null && stochK < 20;
        });

        predicates.put(PresetStrategyEnum.STOCH_OVERBOUGHT, row -> {
            Double stochK = getDouble(row, "Stoch.K");
            return stochK != null && stochK > 80;
        });

        predicates.put(PresetStrategyEnum.MOMENTUM_POSITIVE, row -> {
            Double mom = getDouble(row, "Mom");
            return mom != null && mom > 0;
        });

        predicates.put(PresetStrategyEnum.WILLIAMS_R_OVERSOLD, row -> {
            Double wr = getDouble(row, "W.R");
            return wr != null && wr < -80;
        });

        predicates.put(PresetStrategyEnum.ULTIMATE_OSCILLATOR_STRONG, row -> {
            Double uo = getDouble(row, "UO");
            return uo != null && uo > 50;
        });

        predicates.put(PresetStrategyEnum.CCI_BULLISH_ZONE, row -> {
            Double cci = getDouble(row, "CCI20");
            return cci != null && cci > 100;
        });

        // ==================== TREND ====================
        predicates.put(PresetStrategyEnum.MACD_POSITIVE, row -> {
            Double macd = getDouble(row, "MACD.macd");
            Double signal = getDouble(row, "MACD.signal");
            return macd != null && signal != null && macd > signal && macd > 0;
        });

        predicates.put(PresetStrategyEnum.EMA_TREND_UP, row -> {
            Double close = getDouble(row, "close");
            Double ema20 = getDouble(row, "EMA20");
            Double ema50 = getDouble(row, "EMA50");
            Double ema200 = getDouble(row, "EMA200");
            return close != null && ema20 != null && ema50 != null && ema200 != null
                    && close > ema20 && ema20 > ema50 && ema50 > ema200;
        });

        predicates.put(PresetStrategyEnum.ADX_STRONG_TREND, row -> {
            Double adx = getDouble(row, "ADX");
            Double plusDi = getDouble(row, "ADX+DI");
            Double minusDi = getDouble(row, "ADX-DI");
            return adx != null && plusDi != null && minusDi != null
                    && adx > 25 && plusDi > minusDi;
        });

        predicates.put(PresetStrategyEnum.SMA_ABOVE_ORDER, row -> {
            Double sma50 = getDouble(row, "SMA50");
            Double sma200 = getDouble(row, "SMA200");
            return sma50 != null && sma200 != null && sma50 > sma200;
        });

        predicates.put(PresetStrategyEnum.SMA_BELOW_ORDER, row -> {
            Double sma50 = getDouble(row, "SMA50");
            Double sma200 = getDouble(row, "SMA200");
            return sma50 != null && sma200 != null && sma50 < sma200;
        });

        predicates.put(PresetStrategyEnum.PARABOLIC_SAR_ABOVE, row -> {
            Double close = getDouble(row, "close");
            Double pSar = getDouble(row, "P.SAR");
            return close != null && pSar != null && close > pSar;
        });

        predicates.put(PresetStrategyEnum.ICHIMOKU_ABOVE_CLOUD, row -> {
            Double close = getDouble(row, "close");
            Double bLine = getDouble(row, "Ichimoku.BLine");
            Double cLine = getDouble(row, "Ichimoku.CLine");
            return close != null && bLine != null && cLine != null
                    && close > bLine && close > cLine;
        });

        // ==================== HACIM ====================
        predicates.put(PresetStrategyEnum.VOLUME_SPIKE, row -> {
            Double relVol = getDouble(row, "relative_volume_10d_calc");
            return relVol != null && relVol > 2.0;
        });

        predicates.put(PresetStrategyEnum.HIGH_VOLUME_BREAKOUT, row -> {
            Double relVol = getDouble(row, "relative_volume_10d_calc");
            Double change = getDouble(row, "change");
            return relVol != null && change != null && relVol > 3.0 && change > 2.0;
        });

        predicates.put(PresetStrategyEnum.STRONG_MONEYFLOW, row -> {
            Double mf = getDouble(row, "MoneyFlow");
            return mf != null && mf > 80;
        });

        // ==================== VOLATILITE ====================
        predicates.put(PresetStrategyEnum.BB_SQUEEZE, row -> {
            Double upper = getDouble(row, "BB.upper");
            Double lower = getDouble(row, "BB.lower");
            Double basis = getDouble(row, "BB.basis");
            return upper != null && lower != null && basis != null && basis != 0
                    && (upper - lower) / basis < 0.04;
        });

        predicates.put(PresetStrategyEnum.LOW_VOLATILITY, row -> {
            Double vol = getDouble(row, "Volatility.D");
            return vol != null && vol < 2.0;
        });

        // ==================== KOMPOZIT ====================
        predicates.put(PresetStrategyEnum.PRICE_ABOVE_SMA50, row -> {
            Double close = getDouble(row, "close");
            Double sma50 = getDouble(row, "SMA50");
            return close != null && sma50 != null && close > sma50;
        });

        predicates.put(PresetStrategyEnum.OVERSOLD_BOUNCE, row -> {
            Double rsi = getDouble(row, "RSI");
            Double change = getDouble(row, "change");
            return rsi != null && change != null && rsi < 35 && change > 0;
        });

        predicates.put(PresetStrategyEnum.HIGH_ROC, row -> {
            Double roc = getDouble(row, "ROC");
            return roc != null && roc > 5.0;
        });

        predicates.put(PresetStrategyEnum.DMI_BULLISH, row -> {
            Double plusDi = getDouble(row, "ADX+DI");
            Double minusDi = getDouble(row, "ADX-DI");
            Double adx = getDouble(row, "ADX");
            return plusDi != null && minusDi != null && adx != null
                    && plusDi > minusDi && adx > 20;
        });

        predicates.put(PresetStrategyEnum.TRIX_POSITIVE, row -> {
            Double mom = getDouble(row, "Mom");
            Double roc = getDouble(row, "ROC");
            return mom != null && roc != null && mom > 0 && roc > 0;
        });

        predicates.put(PresetStrategyEnum.VORTEX_BULLISH, row -> {
            Double plusDi = getDouble(row, "ADX+DI");
            Double minusDi = getDouble(row, "ADX-DI");
            return plusDi != null && minusDi != null && plusDi > minusDi;
        });

        predicates.put(PresetStrategyEnum.AROON_UP_TREND, row -> {
            Double aroonUp = getDouble(row, "Aroon.Up");
            Double aroonDown = getDouble(row, "Aroon.Down");
            return aroonUp != null && aroonDown != null && aroonUp > 70 && aroonDown < 30;
        });

        predicates.put(PresetStrategyEnum.COMMODITY_CHANNEL_STRONG, row -> {
            Double cci = getDouble(row, "CCI20");
            return cci != null && cci > 200;
        });

        log.info("[PRESET-STRATEGY-REGISTRY] {} strateji filtresi kayit edildi", predicates.size());
    }

    /**
     * Belirtilen strateji icin filtre predicate'ini dondurur.
     *
     * @param strategy strateji enum degeri
     * @return filtre predicate'i; bulunamazsa her zaman {@code false} donen predicate
     */
    public Predicate<Map<String, Object>> getPredicate(PresetStrategyEnum strategy) {
        return predicates.getOrDefault(strategy, row -> false);
    }

    /**
     * Map'ten null-safe sayi cikarimi yapar.
     *
     * @param row   kolon-deger map'i
     * @param key   kolon adi
     * @return sayi degeri; null veya sayi degilse {@code null}
     */
    private Double getDouble(Map<String, Object> row, String key) {
        Object val = row.get(key);
        if (val instanceof Number n) return n.doubleValue();
        return null;
    }
}
