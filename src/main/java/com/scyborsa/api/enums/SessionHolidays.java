package com.scyborsa.api.enums;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * BIST resmi tatil günleri enum'ı.
 *
 * Tatil Türleri:
 * - Ulusal Bayramlar: 23 Nisan, 19 Mayıs, 30 Ağustos, 29 Ekim
 * - Dini Bayramlar: Ramazan Bayramı, Kurban Bayramı
 * - Diğer: Yılbaşı, 1 Mayıs, 15 Temmuz
 *
 * Yarım Gün (Arefe): Borsa erken kapanır (genelde 13:00)
 */
public enum SessionHolidays {

    // ==================== 2025 TATİLLERİ ====================
    Time_20250328("20250328", true, LocalTime.of(13, 0)),
    Time_20250330("20250330", false, null),
    Time_20250331("20250331", false, null),
    Time_20250423("20250423", false, null),
    Time_20250501("20250501", false, null),
    Time_20250519("20250519", false, null),
    Time_20250605("20250605", true, LocalTime.of(13, 0)),
    Time_20250606("20250606", false, null),
    Time_20250607("20250607", false, null),
    Time_20250608("20250608", false, null),
    Time_20250609("20250609", false, null),
    Time_20250715("20250715", false, null),
    Time_20251028("20251028", true, LocalTime.of(13, 0)),
    Time_20251029("20251029", false, null),

    // ==================== 2026 TATİLLERİ ====================
    Time_20260101("20260101", false, null),
    Time_20260319("20260319", true, LocalTime.of(13, 0)),
    Time_20260320("20260320", false, null),
    Time_20260423("20260423", false, null),
    Time_20260501("20260501", false, null),
    Time_20260519("20260519", false, null),
    Time_20260526("20260526", true, LocalTime.of(13, 0)),
    Time_20260527("20260527", false, null),
    Time_20260528("20260528", false, null),
    Time_20260529("20260529", false, null),
    Time_20260715("20260715", false, null),
    Time_20261028("20261028", true, LocalTime.of(13, 0)),
    Time_20261029("20261029", false, null),
    Time_20261231("20261231", true, LocalTime.of(13, 0)),

    // ==================== 2027 TATİLLERİ ====================
    Time_20270101("20270101", false, null),
    Time_20270308("20270308", true, LocalTime.of(12, 40)),
    Time_20270309("20270309", false, null),
    Time_20270310("20270310", false, null),
    Time_20270311("20270311", false, null),
    Time_20270423("20270423", false, null),
    Time_20270517("20270517", false, null),
    Time_20270518("20270518", false, null),
    Time_20270519("20270519", false, null),
    Time_20270715("20270715", false, null),
    Time_20270830("20270830", false, null),
    Time_20271028("20271028", true, LocalTime.of(12, 40)),
    Time_20271029("20271029", false, null),
    Time_20271231("20271231", true, LocalTime.of(13, 0));

    /** Tatil tarihi (yyyyMMdd formati, orn. "20250423"). */
    private final String dateStr;

    /** Yarim gun (arefe) tatili mi? */
    private final boolean halfDay;

    /** Yarim gun kapanis saati (tam gun tatillerde null). */
    private final LocalTime closingTime;

    /** Tarih format deseni (yyyyMMdd). */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Istanbul saat dilimi. */
    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");

    SessionHolidays(String dateStr, boolean halfDay, LocalTime closingTime) {
        this.dateStr = dateStr;
        this.halfDay = halfDay;
        this.closingTime = closingTime;
    }

    // ==================== STATIC METHODS ====================

    /**
     * Verilen tarihe karsilik gelen tatil enum degerini bulur.
     *
     * @param date aranacak tarih
     * @return eslesen {@link SessionHolidays} degeri, bulunamazsa {@code null}
     */
    private static SessionHolidays findByDate(LocalDate date) {
        String formatted = date.format(DATE_FORMAT);
        for (SessionHolidays holiday : values()) {
            if (holiday.dateStr.equals(formatted)) {
                return holiday;
            }
        }
        return null;
    }

    /**
     * Verilen tarihin tam gun tatil olup olmadigini kontrol eder.
     *
     * <p>Yarim gun (arefe) tatilleri bu metodda {@code false} dondurur.</p>
     *
     * @param date kontrol edilecek tarih
     * @return tam gun tatilse {@code true}
     */
    public static boolean isHoliday(LocalDate date) {
        SessionHolidays holiday = findByDate(date);
        return holiday != null && !holiday.halfDay;
    }

    /**
     * Verilen tarihin yarim gun (arefe) olup olmadigini kontrol eder.
     *
     * @param date kontrol edilecek tarih
     * @return yarim gun tatilse {@code true}
     */
    public static boolean isHalfDay(LocalDate date) {
        SessionHolidays holiday = findByDate(date);
        return holiday != null && holiday.halfDay;
    }

    /**
     * Yarim gun tatillerinde borsanin kapanis saatini dondurur.
     *
     * @param date kontrol edilecek tarih
     * @return yarim gun kapanis saati; yarim gun degilse {@code null}
     */
    public static LocalTime getHalfDayClosingTime(LocalDate date) {
        SessionHolidays holiday = findByDate(date);
        if (holiday != null && holiday.halfDay) {
            return holiday.closingTime;
        }
        return null;
    }

    /**
     * Bugunun tam gun tatil olup olmadigini kontrol eder (Istanbul saat dilimi).
     *
     * @return bugun tam gun tatilse {@code true}
     */
    public static boolean isTodayHoliday() {
        return isHoliday(LocalDate.now(ISTANBUL_ZONE));
    }

    /**
     * Bugunun yarim gun (arefe) olup olmadigini kontrol eder (Istanbul saat dilimi).
     *
     * @return bugun yarim gunse {@code true}
     */
    public static boolean isTodayHalfDay() {
        return isHalfDay(LocalDate.now(ISTANBUL_ZONE));
    }

    /**
     * Verilen tarihin islem yapilmayan gun olup olmadigini kontrol eder.
     *
     * <p>Hafta sonlari (Cumartesi/Pazar) ve tam gun tatiller islem yapilmayan gun sayilir.
     * Yarim gun tatiller islem gunu olarak kabul edilir.</p>
     *
     * @param date kontrol edilecek tarih
     * @return islem yapilmayan gunse {@code true}
     */
    public static boolean isNonTradingDay(LocalDate date) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return true;
        }
        return isHoliday(date);
    }

    /**
     * Verilen tarih ve saatin islem saati icinde olup olmadigini kontrol eder.
     *
     * <p>Asagidaki durumlar {@code false} dondurur:</p>
     * <ul>
     *   <li>Hafta sonu</li>
     *   <li>Tam gun tatil</li>
     *   <li>Yarim gun tatilde kapanis saatinden sonra</li>
     * </ul>
     *
     * @param date kontrol edilecek tarih
     * @param time kontrol edilecek saat
     * @return islem saati icindeyse {@code true}
     */
    public static boolean isTradingTime(LocalDate date, LocalTime time) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return false;
        }
        if (isHoliday(date)) {
            return false;
        }
        LocalTime closing = getHalfDayClosingTime(date);
        if (closing != null && !time.isBefore(closing)) {
            return false;
        }
        return true;
    }

    /**
     * Su anki zamanin islem saati icinde olup olmadigini kontrol eder (Istanbul saat dilimi).
     *
     * @return su an islem saati icindeyse {@code true}
     */
    public static boolean isTradingTimeNow() {
        return isTradingTime(LocalDate.now(ISTANBUL_ZONE), LocalTime.now(ISTANBUL_ZONE));
    }

    /**
     * Bugunun islem gunu olup olmadigini kontrol eder (Istanbul saat dilimi).
     *
     * @return bugun islem gunuyse {@code true}
     */
    public static boolean isTradingDay() {
        return !isNonTradingDay(LocalDate.now(ISTANBUL_ZONE));
    }

    /**
     * Verilen tarihten onceki en yakin islem gununu bulur.
     *
     * <p>Hafta sonlarini ve tam gun tatilleri atlayarak geriye dogru arar.</p>
     *
     * @param date baslangic tarihi (dahil edilmez)
     * @return onceki islem gunu
     */
    public static LocalDate getPreviousTradingDay(LocalDate date) {
        LocalDate prevDay = date.minusDays(1);
        while (isNonTradingDay(prevDay)) {
            prevDay = prevDay.minusDays(1);
        }
        return prevDay;
    }

    /**
     * Verilen tarihten sonraki en yakin islem gununu bulur.
     *
     * <p>Hafta sonlarini ve tam gun tatilleri atlayarak ileriye dogru arar.</p>
     *
     * @param date baslangic tarihi (dahil edilmez)
     * @return sonraki islem gunu
     */
    public static LocalDate getNextTradingDay(LocalDate date) {
        LocalDate nextDay = date.plusDays(1);
        while (isNonTradingDay(nextDay)) {
            nextDay = nextDay.plusDays(1);
        }
        return nextDay;
    }

    // ==================== INSTANCE METHODS ====================

    /**
     * Tatilin tarih string'ini {@code yyyyMMdd} formatinda dondurur.
     *
     * @return tarih string'i (ornegin {@code "20250423"})
     */
    public String getDateStr() {
        return dateStr;
    }

    /**
     * Bu tatilin yarim gun (arefe) olup olmadigini dondurur.
     *
     * @return yarim gunse {@code true}, tam gun tatilse {@code false}
     */
    public boolean isHalfDay() {
        return halfDay;
    }

    /**
     * Yarim gun tatillerinde borsanin kapanis saatini dondurur.
     *
     * @return kapanis saati; tam gun tatillerde {@code null}
     */
    public LocalTime getClosingTime() {
        return closingTime;
    }
}
