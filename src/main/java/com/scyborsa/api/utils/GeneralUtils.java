package com.scyborsa.api.utils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Genel yardımcı metodlar.
 */
public final class GeneralUtils {

    private GeneralUtils() {
    }

    /**
     * Verilen string'in sayisal bir deger olup olmadigini kontrol eder.
     * Ondalikli sayilar dahil {@link Double#parseDouble} ile parse edilebilirlik testi yapar.
     *
     * @param str kontrol edilecek string
     * @return string sayisalsa {@code true}, null/bos veya sayisal degilse {@code false}
     */
    public static boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) return false;
        try {
            Double.parseDouble(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Bugunun tarihini "yyyy-MM-dd" formatinda dondurur.
     *
     * @return bugunun tarihi (ornegin "2026-03-02")
     */
    public static String getToday() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }
}
