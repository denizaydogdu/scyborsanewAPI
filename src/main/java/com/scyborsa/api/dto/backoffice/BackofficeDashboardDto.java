package com.scyborsa.api.dto.backoffice;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Backoffice dashboard KPI verileri DTO sinifi.
 *
 * <p>Dashboard sayfasinda gosterilen istatistiklerin toplu transfer nesnesi.
 * Her biri farkli bir KPI grubunu temsil eden nested class'lardan olusur.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BackofficeDashboardDto {

    /** Hisse istatistikleri. */
    private StockStats stocks;

    /** Icerik istatistikleri (analist, kurum, sektor). */
    private ContentStats content;

    /** Tarama istatistikleri. */
    private ScreenerStats screener;

    /** Sistem bilgileri. */
    private SystemSummary system;

    /**
     * Hisse istatistikleri.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StockStats {
        /** Toplam hisse sayisi. */
        private long total;
        /** Aktif hisse sayisi. */
        private long active;
        /** Yasakli hisse sayisi. */
        private long banned;
    }

    /**
     * Icerik istatistikleri.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContentStats {
        /** Analist sayisi. */
        private long analistCount;
        /** Kurum sayisi. */
        private long kurumCount;
        /** Sektor sayisi. */
        private long sektorCount;
        /** Araci kurum sayisi. */
        private long araciKurumCount;
    }

    /**
     * Tarama istatistikleri.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScreenerStats {
        /** Bugunun toplam tarama sayisi. */
        private long todayTotal;
        /** Telegram'a gonderilen tarama sayisi. */
        private long telegramSent;
        /** Telegram'a gonderilmemis tarama sayisi. */
        private long telegramUnsent;
        /** TP/SL kontrolu bekleyen tarama sayisi. */
        private long tpSlPending;
        /** Scan body toplam sayisi. */
        private long scanBodyCount;
        /** Telegram gonderimi aktif mi. */
        private boolean telegramEnabled;
    }

    /**
     * Sistem bilgileri.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SystemSummary {
        /** Uygulama uptime (milisaniye). */
        private long uptimeMs;
        /** Kullanilan heap boyutu (byte). */
        private long usedHeapBytes;
        /** Maksimum heap boyutu (byte). */
        private long maxHeapBytes;
        /** Aktif Spring profili. */
        private String activeProfile;
    }
}
