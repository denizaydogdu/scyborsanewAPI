package com.scyborsa.api.enums;

import lombok.Getter;

import java.time.LocalTime;
import java.time.ZoneId;

/**
 * BIST işlem saatleri için zaman dilimi enum'ı.
 *
 * <p>BIST işlem saatleri boyunca (09:56 - 18:12) 5 dakikalık aralıklarla
 * tanımlanmış zaman dilimlerini içerir. Tarama zamanlaması ve cache key
 * oluşturma için kullanılır.</p>
 *
 * <h3>BIST İşlem Saatleri:</h3>
 * <ul>
 *   <li><b>Açılış:</b> 09:40</li>
 *   <li><b>Sürekli İşlem:</b> 10:00 - 18:00</li>
 *   <li><b>Kapanış Seansı:</b> 18:00 - 18:12</li>
 * </ul>
 *
 * @see com.scyborsa.api.service.job.BistGunlukTaramalarJob
 */
@Getter
public enum ScreenerTimesEnum {

    // ==================== PRE-MARKET ====================
    TIME_UNKNOWN("00:00"),
    TIME_09_56("09:56"),

    // ==================== 10:00 - 10:55 ====================
    TIME_10_00("10:00"),
    TIME_10_01("10:01"),
    TIME_10_05("10:05"),
    TIME_10_10("10:10"),
    TIME_10_15("10:15"),
    TIME_10_20("10:20"),
    TIME_10_25("10:25"),
    TIME_10_30("10:30"),
    TIME_10_35("10:35"),
    TIME_10_40("10:40"),
    TIME_10_45("10:45"),
    TIME_10_50("10:50"),
    TIME_10_55("10:55"),

    // ==================== 11:00 - 11:55 ====================
    TIME_11_00("11:00"),
    TIME_11_05("11:05"),
    TIME_11_10("11:10"),
    TIME_11_15("11:15"),
    TIME_11_20("11:20"),
    TIME_11_25("11:25"),
    TIME_11_30("11:30"),
    TIME_11_35("11:35"),
    TIME_11_40("11:40"),
    TIME_11_45("11:45"),
    TIME_11_50("11:50"),
    TIME_11_55("11:55"),

    // ==================== 12:00 - 12:55 ====================
    TIME_12_00("12:00"),
    TIME_12_05("12:05"),
    TIME_12_10("12:10"),
    TIME_12_15("12:15"),
    TIME_12_20("12:20"),
    TIME_12_25("12:25"),
    TIME_12_30("12:30"),
    TIME_12_35("12:35"),
    TIME_12_40("12:40"),
    TIME_12_45("12:45"),
    TIME_12_50("12:50"),
    TIME_12_55("12:55"),

    // ==================== 13:00 - 13:55 ====================
    TIME_13_00("13:00"),
    TIME_13_05("13:05"),
    TIME_13_10("13:10"),
    TIME_13_15("13:15"),
    TIME_13_20("13:20"),
    TIME_13_25("13:25"),
    TIME_13_30("13:30"),
    TIME_13_35("13:35"),
    TIME_13_40("13:40"),
    TIME_13_45("13:45"),
    TIME_13_50("13:50"),
    TIME_13_55("13:55"),

    // ==================== 14:00 - 14:55 ====================
    TIME_14_00("14:00"),
    TIME_14_05("14:05"),
    TIME_14_10("14:10"),
    TIME_14_12("14:12"),
    TIME_14_15("14:15"),
    TIME_14_20("14:20"),
    TIME_14_25("14:25"),
    TIME_14_30("14:30"),
    TIME_14_35("14:35"),
    TIME_14_40("14:40"),
    TIME_14_45("14:45"),
    TIME_14_50("14:50"),
    TIME_14_55("14:55"),

    // ==================== 15:00 - 15:55 ====================
    TIME_15_00("15:00"),
    TIME_15_05("15:05"),
    TIME_15_10("15:10"),
    TIME_15_15("15:15"),
    TIME_15_20("15:20"),
    TIME_15_25("15:25"),
    TIME_15_30("15:30"),
    TIME_15_35("15:35"),
    TIME_15_40("15:40"),
    TIME_15_45("15:45"),
    TIME_15_50("15:50"),
    TIME_15_55("15:55"),

    // ==================== 16:00 - 16:55 ====================
    TIME_16_00("16:00"),
    TIME_16_04("16:04"),
    TIME_16_05("16:05"),
    TIME_16_10("16:10"),
    TIME_16_15("16:15"),
    TIME_16_20("16:20"),
    TIME_16_25("16:25"),
    TIME_16_30("16:30"),
    TIME_16_35("16:35"),
    TIME_16_40("16:40"),
    TIME_16_45("16:45"),
    TIME_16_48("16:48"),
    TIME_16_50("16:50"),
    TIME_16_55("16:55"),

    // ==================== 17:00 - 17:59 ====================
    TIME_17_00("17:00"),
    TIME_17_05("17:05"),
    TIME_17_10("17:10"),
    TIME_17_15("17:15"),
    TIME_17_20("17:20"),
    TIME_17_25("17:25"),
    TIME_17_30("17:30"),
    TIME_17_35("17:35"),
    TIME_17_40("17:40"),
    TIME_17_45("17:45"),
    TIME_17_50("17:50"),
    TIME_17_55("17:55"),
    TIME_17_59("17:59"),

    // ==================== KAPANIŞ SEANSI ====================
    TIME_18_00("18:00"),
    TIME_18_01("18:01"),
    TIME_18_05("18:05"),
    TIME_18_06("18:06"),
    TIME_18_07("18:07"),
    TIME_18_12("18:12");

    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 40);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(18, 15);

    /** Zaman diliminin string gösterimi (ör. "10:30"). */
    private final String timeStr;

    ScreenerTimesEnum(String timeStr) {
        this.timeStr = timeStr;
    }

    /**
     * String zaman değerinden ScreenerTimesEnum bulur.
     *
     * @param timeStr aranacak zaman (ör. "10:30")
     * @return eşleşen enum değeri
     * @throws IllegalArgumentException eşleşme bulunamazsa
     */
    public static ScreenerTimesEnum fromName(String timeStr) {
        for (ScreenerTimesEnum time : values()) {
            if (time.getTimeStr().equalsIgnoreCase(timeStr)) {
                return time;
            }
        }
        throw new IllegalArgumentException("Geçersiz screener time: " + timeStr);
    }

    /**
     * LocalTime değerinden en yakın (önceki) ScreenerTimesEnum'u bulur.
     *
     * <p>Önce tam eşleşme arar; yoksa verilen zamandan önceki en yakın
     * enum değerini döndürür.</p>
     *
     * @param time aranacak LocalTime
     * @return eşleşen veya en yakın önceki enum; hiçbiri uymazsa {@code null}
     */
    public static ScreenerTimesEnum fromTime(LocalTime time) {
        if (time == null) return null;
        String timeStr = String.format("%02d:%02d", time.getHour(), time.getMinute());

        for (ScreenerTimesEnum screenTime : values()) {
            if (screenTime.getTimeStr().equals(timeStr)) {
                return screenTime;
            }
        }

        // Zaman değerine göre en yakın önceki enum'u bul (declaration sırasına bağımlı değil)
        ScreenerTimesEnum closest = null;
        LocalTime closestTime = null;
        for (ScreenerTimesEnum screenTime : values()) {
            LocalTime enumTime = screenTime.toLocalTime();
            if (enumTime != null && !enumTime.isAfter(time)) {
                if (closestTime == null || enumTime.isAfter(closestTime)) {
                    closest = screenTime;
                    closestTime = enumTime;
                }
            }
        }
        return closest;
    }

    /**
     * Şu anki Istanbul zamanına göre ScreenerTimesEnum döndürür.
     *
     * @return şu anki zamana en yakın enum değeri
     */
    public static ScreenerTimesEnum now() {
        return fromTime(LocalTime.now(ISTANBUL_ZONE));
    }

    /**
     * Enum değerini LocalTime'a çevirir.
     *
     * @return LocalTime karşılığı; parse edilemezse {@code null}
     */
    public LocalTime toLocalTime() {
        try {
            String[] parts = timeStr.split(":");
            return LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Bu zamanın BIST işlem saatleri içinde olup olmadığını kontrol eder.
     *
     * @return {@code true} ise 09:40 - 18:15 arası
     */
    public boolean isMarketHours() {
        LocalTime time = toLocalTime();
        if (time == null) return false;
        return !time.isBefore(MARKET_OPEN) && !time.isAfter(MARKET_CLOSE);
    }
}
