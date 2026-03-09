package com.scyborsa.api.utils;

import com.scyborsa.api.enums.SessionHolidays;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * BIST işlem takvimi utility.
 * Tatil, yarım gün ve işlem saati kontrolü.
 */
@Slf4j
public final class BistTradingCalendar {

    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");

    private BistTradingCalendar() {
    }

    /**
     * Bugunun islem gunu olup olmadigini ve su anki saatin islem saati icinde olup olmadigini kontrol eder.
     * Tatil ve yarim gun kurallarini dikkate alir.
     *
     * @return islem yapilabilir zamandaysa {@code true}
     */
    public static boolean isNotOffDay() {
        LocalDate today = LocalDate.now(ISTANBUL_ZONE);
        LocalTime now = LocalTime.now(ISTANBUL_ZONE);
        return SessionHolidays.isTradingTime(today, now);
    }

    /**
     * Bugunun BIST islem gunu olup olmadigini kontrol eder.
     * Hafta sonu ve resmi tatiller islem gunu degildir.
     *
     * @return bugun islem gunuyse {@code true}
     */
    public static boolean isTradingDay() {
        return SessionHolidays.isTradingDay();
    }

    /**
     * Bugunun resmi tatil olup olmadigini kontrol eder.
     *
     * @return bugun tatilse {@code true}
     */
    public static boolean isHoliday() {
        return SessionHolidays.isTodayHoliday();
    }

    /**
     * Bugunun yarim gun (kisa seans) olup olmadigini kontrol eder.
     * Bayram arefe gunleri gibi ozel gunlerde borsa yarim gun calisir.
     *
     * @return bugun yarim gunse {@code true}
     */
    public static boolean isHalfDay() {
        return SessionHolidays.isTodayHalfDay();
    }

    /**
     * Su anki zamanin BIST islem saatleri icinde olup olmadigini kontrol eder.
     * Istanbul zaman dilimini kullanir.
     *
     * @return su an islem saati icindeyse {@code true}
     */
    public static boolean isTradingTimeNow() {
        return SessionHolidays.isTradingTimeNow();
    }

    /**
     * Bugun yarim gunse kapanis saatini dondurur.
     *
     * @return yarim gun kapanis saati; yarim gun degilse {@code null} donebilir
     */
    public static LocalTime getHalfDayClosingTime() {
        return SessionHolidays.getHalfDayClosingTime(LocalDate.now(ISTANBUL_ZONE));
    }

    /**
     * Bugunun bir onceki islem gununu dondurur.
     * Hafta sonlari ve tatil gunlerini atlayarak geri gider.
     *
     * @return bir onceki islem gunu tarihi
     */
    public static LocalDate getPreviousTradingDay() {
        return SessionHolidays.getPreviousTradingDay(LocalDate.now(ISTANBUL_ZONE));
    }

    /**
     * Verilen tarihin bir onceki islem gununu dondurur.
     * Hafta sonlari ve tatil gunlerini atlayarak geri gider.
     *
     * @param date referans tarih
     * @return verilen tarihten bir onceki islem gunu
     */
    public static LocalDate getPreviousTradingDay(LocalDate date) {
        return SessionHolidays.getPreviousTradingDay(date);
    }
}
