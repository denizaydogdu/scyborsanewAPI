package com.scyborsa.api.service.telegram;

/**
 * Telegram mesajları için Türkçe hacim ve yüzde formatlama utility sınıfı.
 *
 * <p>Tüm Telegram builder'ları tarafından ortak kullanılır.
 * Hacim değerlerini Türkçe format (Milyar/Milyon/Bin) ile formatlar.</p>
 */
public final class TelegramVolumeFormatter {

    private TelegramVolumeFormatter() {}

    /**
     * Hacim değerini Türkçe format ile formatlar.
     *
     * @param volume hacim değeri (TL cinsinden)
     * @return formatlanmış string (ör: "1.87 Milyar", "543 Milyon", "12.5 Bin")
     */
    public static String formatVolumeTurkish(long volume) {
        long abs = Math.abs(volume);
        String sign = volume < 0 ? "-" : "";
        if (abs >= 1_000_000_000L) {
            return sign + String.format("%.2f Milyar", abs / 1_000_000_000.0);
        } else if (abs >= 1_000_000L) {
            return sign + String.format("%.0f Milyon", abs / 1_000_000.0);
        } else if (abs >= 1_000L) {
            return sign + String.format("%.1f Bin", abs / 1_000.0);
        }
        return sign + String.valueOf(abs);
    }

    /**
     * Double hacim değerini Türkçe format ile formatlar.
     *
     * @param volume hacim değeri
     * @return formatlanmış string
     */
    public static String formatVolumeTurkish(double volume) {
        return formatVolumeTurkish(Math.round(volume));
    }

    /**
     * HTML'de kullanılmak üzere metin escape eder.
     *
     * <p>Not: TelegramMessageFormatter'da aynı mantık private method olarak mevcut.
     * Bu method tüm Telegram builder'ları tarafından ortak kullanılır.</p>
     *
     * @param text escape edilecek metin
     * @return HTML-safe metin
     */
    public static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
