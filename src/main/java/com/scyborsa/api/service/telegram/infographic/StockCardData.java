package com.scyborsa.api.service.telegram.infographic;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Telegram hisse infografik kartı için veri modeli.
 *
 * <p>Tüm enrichment alanları nullable'dır; eksik veri durumunda
 * ilgili bölüm graceful degradation ile atlanır.</p>
 *
 * @see StockCardRenderer
 */
@Data
@Builder
public class StockCardData {

    /** Hisse kodu (ör. "THYAO"). */
    private String stockName;

    /** Son fiyat (TL). */
    private Double price;

    /** Günlük değişim yüzdesi (ör. 2.35 veya -1.10). */
    private Double changePercent;

    // ----- Enrichment verileri (hepsi nullable — graceful degradation) -----

    /** Fon pozisyonları listesi. */
    private List<FonPozisyonItem> fonPozisyonlari;

    /** Alıcı kurumlar listesi. */
    private List<KurumItem> aliciKurumlar;

    /** Satıcı kurumlar listesi. */
    private List<KurumItem> saticiKurumlar;

    /** Takas dağılımı listesi. */
    private List<TakasItem> takasDagilimi;

    /** Alış emirleri listesi. */
    private List<EmirItem> alisEmirler;

    /** Satış emirleri listesi. */
    private List<EmirItem> satisEmirler;

    // ----- Tarama metadata -----

    /** Ortak tarama sayısı. */
    private int screenerCount;

    /** İlk sinyal zamanı (ör. "16:00"). */
    private String firstSignalTime;

    /** Son sinyal zamanı (ör. "16:00"). */
    private String lastSignalTime;

    /** Zaman damgası (ör. "16:01:59 - 25 Mart 2026"). */
    private String timestamp;

    /** Listede gösterilmeyen ek fon sayısı (ör. "... ve 12 fon daha"). */
    private int extraFonCount;

    // ===================== İç veri sınıfları =====================

    /**
     * Fon pozisyonu satır verisi.
     */
    @Data
    @Builder
    public static class FonPozisyonItem {

        /** Fon kodu (ör. "TYH"). */
        private String fonKodu;

        /** Formatlanmış lot miktarı (ör. "316.4K lot"). */
        private String lotFormatted;

        /** Ağırlık yüzdesi (ör. "1.22%"). */
        private String agirlik;
    }

    /**
     * Kurum dağılımı satır verisi.
     */
    @Data
    @Builder
    public static class KurumItem {

        /** Kurum adı. */
        private String kurumAdi;

        /** Formatlanmış hacim (ör. "+109.38 TL" veya "-105.27 TL"). */
        private String formattedVolume;
    }

    /**
     * Takas dağılımı satır verisi.
     */
    @Data
    @Builder
    public static class TakasItem {

        /** Saklama kuruluşu kodu (ör. "YAT", "YBN"). */
        private String custodianCode;

        /** Formatlanmış değer (ör. "6.74 Milyon TL"). */
        private String formattedValue;

        /** Yüzde oranı (0.0 - 1.0 aralığında, ör. 0.2405). */
        private double percentage;
    }

    /**
     * Emir defteri satır verisi.
     */
    @Data
    @Builder
    public static class EmirItem {

        /** Emir zamanı (ör. "16:01:58"). */
        private String time;

        /** Emir fiyatı (ör. "111.00₺"). */
        private String price;

        /** Lot miktarı (ör. "200"). */
        private String lot;

        /** Gönderen kurum (ör. "Midas"). */
        private String from;

        /** Hedef kurum (ör. "BofA"). */
        private String to;
    }
}
