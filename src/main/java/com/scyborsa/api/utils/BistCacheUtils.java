package com.scyborsa.api.utils;

import com.scyborsa.api.enums.SessionHolidays;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Borsa seans durumuna gore cache yardimci methodlari.
 *
 * <p>Adaptif TTL hesaplama, islem gunu tarih cozumleme ve Turkce tarih formatlama saglar.
 * Birden fazla servisin tekrarlayan cache mantigi bu sinifta merkezlestirilmistir.</p>
 *
 * @see SessionHolidays
 */
public final class BistCacheUtils {

    /** Istanbul saat dilimi (Europe/Istanbul). */
    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");
    /** ISO tarih format deseni (yyyy-MM-dd). */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** Turkce locale (tarih formatlama icin). */
    private static final Locale TR = new Locale("tr", "TR");

    private BistCacheUtils() {
        // Utility class — ornekleme engeli
    }

    /**
     * Seans durumuna gore adaptif cache TTL hesaplar.
     *
     * <p>Seans ici (09:50-18:25): {@code liveTtlMs}, seans disi/tatil: {@code offhoursTtlMs}.
     * Yarim gun kapanisini da dikkate alir (kapanistan 10 dakika sonra seans disi sayilir).</p>
     *
     * @param liveTtlMs     seans ici TTL (milisaniye)
     * @param offhoursTtlMs seans disi TTL (milisaniye)
     * @return hesaplanan TTL (milisaniye)
     */
    public static long getAdaptiveTTL(long liveTtlMs, long offhoursTtlMs) {
        LocalDate today = LocalDate.now(ISTANBUL_ZONE);
        LocalTime now = LocalTime.now(ISTANBUL_ZONE);

        // Tatil / Hafta sonu
        if (SessionHolidays.isNonTradingDay(today)) {
            return offhoursTtlMs;
        }

        // Yarim gun kapanisi gectiyse
        LocalTime halfClose = SessionHolidays.getHalfDayClosingTime(today);
        if (halfClose != null && now.isAfter(halfClose.plusMinutes(10))) {
            return offhoursTtlMs;
        }

        // Seans oncesi (00:00-09:50)
        if (now.isBefore(LocalTime.of(9, 50))) {
            return offhoursTtlMs;
        }

        // Seans sonrasi (18:25+)
        if (now.isAfter(LocalTime.of(18, 25))) {
            return offhoursTtlMs;
        }

        // Seans ici
        return liveTtlMs;
    }

    /**
     * Tarih parametresini cozumler. Null veya bos ise bugunu veya onceki islem gunununu dondurur.
     *
     * <p>Tatil veya hafta sonu ise geriye dogru en yakin islem gununu bulur
     * (maks 10 iterasyon guvenlik siniri).</p>
     *
     * @param date kullanicidan gelen tarih (nullable)
     * @return cozumlenmis tarih (yyyy-MM-dd formatinda)
     */
    public static String resolveDate(String date) {
        if (date != null && !date.isBlank()) {
            return date;
        }
        LocalDate today = LocalDate.now(ISTANBUL_ZONE);
        int maxIterations = 10;
        while (SessionHolidays.isNonTradingDay(today) && maxIterations-- > 0) {
            today = today.minusDays(1);
        }
        return today.format(DATE_FORMAT);
    }

    /**
     * ISO tarih stringini Turkce formata cevirir (orn: "10 Mart 2026").
     *
     * <p>Null, bos veya parse edilemeyen tarihler icin orijinal string dondurulur.</p>
     *
     * @param dateStr ISO tarih (yyyy-MM-dd)
     * @return Turkce formatlanmis tarih
     */
    public static String formatTurkishDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return "";
        }
        try {
            LocalDate d = LocalDate.parse(dateStr, DATE_FORMAT);
            String monthName = d.getMonth().getDisplayName(TextStyle.FULL, TR);
            // Ilk harfi buyut
            monthName = monthName.substring(0, 1).toUpperCase(TR) + monthName.substring(1);
            return d.getDayOfMonth() + " " + monthName + " " + d.getYear();
        } catch (Exception e) {
            return dateStr;
        }
    }
}
