package com.scyborsa.api.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * BIST Fiyat Hesaplama Utility
 *
 * BIST fiyat adımı (tick size) kurallarına uygun fiyat hesaplamaları yapar.
 *
 * Fiyat Adımları:
 * - 0.00 - 19.99: 0.01
 * - 20.00 - 49.99: 0.02
 * - 50.00 - 99.99: 0.05
 * - 100.00 - 249.99: 0.10
 * - 250.00 - 499.99: 0.25
 * - 500.00 - 999.99: 0.50
 * - 1000.00 - 2499.99: 1.00
 * - 2500.00+: 2.50
 */
public final class BistPriceUtils {

    private BistPriceUtils() {
    }

    // ==================== TICK SIZE ====================

    /**
     * Verilen fiyata uygun BIST fiyat adimini (tick size) dondurur.
     *
     * @param price fiyat degeri (null veya negatif olamaz)
     * @return ilgili fiyat bandina karsilik gelen tick size
     * @throws IllegalArgumentException fiyat null veya negatifse
     */
    public static BigDecimal getTickSize(BigDecimal price) {
        if (price == null || price.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Geçersiz fiyat: " + price);
        }

        if (price.compareTo(new BigDecimal("20")) < 0) {
            return new BigDecimal("0.01");
        } else if (price.compareTo(new BigDecimal("50")) < 0) {
            return new BigDecimal("0.02");
        } else if (price.compareTo(new BigDecimal("100")) < 0) {
            return new BigDecimal("0.05");
        } else if (price.compareTo(new BigDecimal("250")) < 0) {
            return new BigDecimal("0.1");
        } else if (price.compareTo(new BigDecimal("500")) < 0) {
            return new BigDecimal("0.25");
        } else if (price.compareTo(new BigDecimal("1000")) < 0) {
            return new BigDecimal("0.5");
        } else if (price.compareTo(new BigDecimal("2500")) < 0) {
            return new BigDecimal("1");
        } else {
            return new BigDecimal("2.5");
        }
    }

    /**
     * Fiyati bir ust tick seviyesine yuvarlar (CEILING).
     *
     * @param price yuvarlanacak fiyat
     * @return tick kuralina uygun yukari yuvarlanmis fiyat
     */
    public static BigDecimal roundUpToTick(BigDecimal price) {
        BigDecimal tickSize = getTickSize(price);
        BigDecimal divided = price.divide(tickSize, 0, RoundingMode.CEILING);
        return divided.multiply(tickSize).setScale(tickSize.scale(), RoundingMode.HALF_UP);
    }

    /**
     * Fiyati bir alt tick seviyesine yuvarlar (FLOOR).
     *
     * @param price yuvarlanacak fiyat
     * @return tick kuralina uygun asagi yuvarlanmis fiyat
     */
    public static BigDecimal roundDownToTick(BigDecimal price) {
        BigDecimal tickSize = getTickSize(price);
        BigDecimal divided = price.divide(tickSize, 0, RoundingMode.FLOOR);
        return divided.multiply(tickSize).setScale(tickSize.scale(), RoundingMode.HALF_UP);
    }

    /**
     * Fiyati en yakin tick seviyesine yuvarlar (HALF_UP).
     *
     * @param price yuvarlanacak fiyat
     * @return tick kuralina uygun en yakin fiyat
     */
    public static BigDecimal roundToTick(BigDecimal price) {
        BigDecimal tickSize = getTickSize(price);
        BigDecimal divided = price.divide(tickSize, 0, RoundingMode.HALF_UP);
        return divided.multiply(tickSize).setScale(tickSize.scale(), RoundingMode.HALF_UP);
    }

    // ==================== PERCENTAGE CALCULATIONS ====================

    /**
     * Verilen fiyat icin %0.5, %1 ve %1.5 artis seviyelerini hesaplar.
     * Her seviye tick kuralina uygun olarak yuvarlanir.
     *
     * @param price referans fiyat
     * @return uc adet yukari hedef fiyat listesi
     */
    public static List<BigDecimal> calculateTopThreePrices(BigDecimal price) {
        BigDecimal[] percentages = {
                new BigDecimal("0.005"),
                new BigDecimal("0.01"),
                new BigDecimal("0.015")
        };
        List<BigDecimal> results = new ArrayList<>();
        for (BigDecimal percentage : percentages) {
            results.add(calculatePriceIncrease(price, percentage));
        }
        return results;
    }

    /**
     * Verilen fiyat icin %0.5, %1 ve %1.5 dusus seviyelerini hesaplar.
     * Her seviye tick kuralina uygun olarak yuvarlanir.
     *
     * @param price referans fiyat
     * @return uc adet asagi hedef fiyat listesi
     */
    public static List<BigDecimal> calculateBottomThreePrices(BigDecimal price) {
        BigDecimal[] percentages = {
                new BigDecimal("0.005"),
                new BigDecimal("0.01"),
                new BigDecimal("0.015")
        };
        List<BigDecimal> results = new ArrayList<>();
        for (BigDecimal percentage : percentages) {
            results.add(calculatePriceDecrease(price, percentage));
        }
        return results;
    }

    /**
     * Fiyati belirli bir yuzde kadar arttirir, tick adimlarini tek tek izleyerek.
     * Fiyat bandi degisimlerini (tick size gecisleri) dogru sekilde yonetir.
     *
     * @param price baslangic fiyati
     * @param percentage artis yuzdesi (ornegin 0.01 = %1)
     * @return tick kuralina uygun arttirilmis fiyat
     */
    public static BigDecimal calculatePriceIncrease(BigDecimal price, BigDecimal percentage) {
        BigDecimal threshold = price.add(price.multiply(percentage))
                .setScale(8, RoundingMode.HALF_UP);

        BigDecimal current = price;
        BigDecimal prevTickSize = BigDecimal.ZERO;

        while (current.compareTo(threshold) < 0) {
            BigDecimal tickSize = getTickSize(current);
            if (!tickSize.equals(prevTickSize)) {
                current = current.setScale(tickSize.scale(), RoundingMode.HALF_UP);
                prevTickSize = tickSize;
            }
            current = current.add(tickSize)
                    .setScale(tickSize.scale(), RoundingMode.HALF_UP);
        }
        return current;
    }

    /**
     * Fiyati belirli bir yuzde kadar dusurur, tick adimlarini tek tek izleyerek.
     * Fiyat bandi degisimlerini (tick size gecisleri) dogru sekilde yonetir.
     *
     * @param price baslangic fiyati
     * @param percentage dusus yuzdesi (ornegin 0.01 = %1)
     * @return tick kuralina uygun dusurulmus fiyat
     */
    public static BigDecimal calculatePriceDecrease(BigDecimal price, BigDecimal percentage) {
        BigDecimal threshold = price.subtract(price.multiply(percentage))
                .setScale(8, RoundingMode.HALF_UP);

        BigDecimal current = price;
        BigDecimal prevTickSize = BigDecimal.ZERO;

        while (current.compareTo(threshold) > 0) {
            BigDecimal tickSize = getTickSize(current);
            if (!tickSize.equals(prevTickSize)) {
                current = current.setScale(tickSize.scale(), RoundingMode.HALF_UP);
                prevTickSize = tickSize;
            }
            current = current.subtract(tickSize)
                    .setScale(tickSize.scale(), RoundingMode.HALF_UP);
        }
        return current;
    }

    // ==================== PRICE LEVELS ====================

    /**
     * Verilen fiyat etrafinda varsayilan 18 seviyeli fiyat merdiveni olusturur.
     * Orta nokta verilen fiyattir; ust ve alt yonde tick adimiyla seviyeler eklenir.
     *
     * @param price merkez fiyat
     * @return yukari + merkez + asagi olmak uzere toplam (2*18+1) seviyeli fiyat listesi
     */
    public static List<BigDecimal> generatePriceLevels(BigDecimal price) {
        return generatePriceLevels(price, 18);
    }

    /**
     * Verilen fiyat etrafinda belirtilen seviye sayisiyla fiyat merdiveni olusturur.
     * Orta nokta verilen fiyattir; ust ve alt yonde tick adimiyla seviyeler eklenir.
     *
     * @param price  merkez fiyat
     * @param levels her yonde olusturulacak seviye sayisi
     * @return yukari + merkez + asagi olmak uzere toplam (2*levels+1) seviyeli fiyat listesi
     */
    public static List<BigDecimal> generatePriceLevels(BigDecimal price, int levels) {
        BigDecimal tickSize = getTickSize(price);
        List<BigDecimal> result = new ArrayList<>();

        for (int i = levels; i >= 1; i--) {
            BigDecimal increment = tickSize.multiply(BigDecimal.valueOf(i));
            result.add(price.add(increment).setScale(tickSize.scale(), RoundingMode.HALF_UP));
        }

        result.add(price.setScale(tickSize.scale(), RoundingMode.HALF_UP));

        for (int i = 1; i <= levels; i++) {
            BigDecimal decrement = tickSize.multiply(BigDecimal.valueOf(i));
            result.add(price.subtract(decrement).setScale(tickSize.scale(), RoundingMode.HALF_UP));
        }
        return result;
    }

    // ==================== VALIDATION ====================

    /**
     * Fiyatin BIST tick kuralina uygun gecerli bir fiyat olup olmadigini kontrol eder.
     * Fiyat pozitif olmali ve tick size'a tam bolunebilmelidir.
     *
     * @param price kontrol edilecek fiyat
     * @return fiyat gecerliyse {@code true}, degilse {@code false}
     */
    public static boolean isValidPrice(BigDecimal price) {
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        BigDecimal tickSize = getTickSize(price);
        return price.remainder(tickSize).compareTo(BigDecimal.ZERO) == 0;
    }

    /**
     * Gecersiz bir fiyati en yakin tick seviyesine yuvarlayarak gecerli hale getirir.
     *
     * @param price gecerli hale getirilecek fiyat (pozitif olmali)
     * @return tick kuralina uygun yuvarlanmis fiyat
     * @throws IllegalArgumentException fiyat null veya sifir/negatifse
     */
    public static BigDecimal makeValidPrice(BigDecimal price) {
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Geçersiz fiyat: " + price);
        }
        return roundToTick(price);
    }

    // ==================== STOP LOSS / TAKE PROFIT ====================

    /**
     * Giris fiyati ve zarar durdurma yuzdesi ile stop loss fiyatini hesaplar.
     * Sonuc asagi yonde tick kuralina uygun yuvarlanir.
     *
     * @param entryPrice      pozisyon giris fiyati
     * @param stopLossPercent zarar durdurma yuzdesi (ornegin 0.05 = %5)
     * @return tick kuralina uygun stop loss fiyati
     */
    public static BigDecimal calculateStopLoss(BigDecimal entryPrice, BigDecimal stopLossPercent) {
        BigDecimal targetPrice = entryPrice.multiply(BigDecimal.ONE.subtract(stopLossPercent));
        return roundDownToTick(targetPrice);
    }

    /**
     * Giris fiyati ve kar alma yuzdesi ile take profit fiyatini hesaplar.
     * Sonuc yukari yonde tick kuralina uygun yuvarlanir.
     *
     * @param entryPrice        pozisyon giris fiyati
     * @param takeProfitPercent kar alma yuzdesi (ornegin 0.10 = %10)
     * @return tick kuralina uygun take profit fiyati
     */
    public static BigDecimal calculateTakeProfit(BigDecimal entryPrice, BigDecimal takeProfitPercent) {
        BigDecimal targetPrice = entryPrice.multiply(BigDecimal.ONE.add(takeProfitPercent));
        return roundUpToTick(targetPrice);
    }

    // ==================== FORMATTING ====================

    /**
     * Fiyati tick size'a uygun ondalik basamak sayisiyla formatlar.
     * Null fiyat icin "0.00" dondurur.
     *
     * @param price formatlanacak fiyat
     * @return formatlanmis fiyat string'i
     */
    public static String formatPrice(BigDecimal price) {
        if (price == null) return "0.00";
        BigDecimal tickSize = getTickSize(price);
        return price.setScale(tickSize.scale(), RoundingMode.HALF_UP).toPlainString();
    }
}
