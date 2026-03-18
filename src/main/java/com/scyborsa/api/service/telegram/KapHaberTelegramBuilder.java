package com.scyborsa.api.service.telegram;

import com.scyborsa.api.dto.kap.KapNewsItemDto;
import com.scyborsa.api.dto.kap.KapRelatedSymbolDto;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * KAP Haber Telegram mesaj builder'i.
 *
 * <p>Her KAP haberi icin ayri bir HTML mesaj olusturur.
 * Ilgili semboller ve kaynak bilgisi eklenir.
 * Detay icerigi varsa HTML'den duz metne donusturulerek eklenir.</p>
 *
 * @see com.scyborsa.api.service.job.KapHaberTelegramJob
 */
@Component
public class KapHaberTelegramBuilder {

    /** Detay icerik maksimum karakter siniri. */
    private static final int MAX_DETAIL_LENGTH = 800;

    /**
     * Tek bir KAP haberi icin Telegram mesaji olusturur.
     *
     * @param item KAP haber DTO'su
     * @return HTML formatinda Telegram mesaji, item null ise null
     */
    public String build(KapNewsItemDto item) {
        return build(item, null);
    }

    /**
     * KAP haberi icin zenginlestirilmis Telegram mesaji olusturur.
     *
     * @param item          KAP haber DTO'su
     * @param detailContent opsiyonel detay icerigi (HTML veya duz metin, HaberDetay'dan)
     * @return HTML formatinda Telegram mesaji, item null ise null
     */
    public String build(KapNewsItemDto item, String detailContent) {
        if (item == null || item.getId() == null || item.getTitle() == null) {
            return null;
        }

        var sb = new StringBuilder();

        // Header: 📢 KAP • HISSE (SC formatı)
        sb.append("\uD83D\uDCE2 <b>KAP</b>");
        String companyCode = extractCompanyCode(item);
        if (companyCode != null) {
            sb.append(" \u2022 <b>").append(TelegramVolumeFormatter.escapeHtml(companyCode)).append("</b>");
        }
        sb.append("\n");

        // Saat
        if (item.getFormattedPublished() != null && !item.getFormattedPublished().isEmpty()) {
            sb.append("\uD83D\uDD50 ").append(TelegramVolumeFormatter.escapeHtml(item.getFormattedPublished())).append("\n");
        }

        // Detay içerik (HTML → düz metin)
        if (detailContent != null && !detailContent.isBlank()) {
            String plainText = htmlToPlainText(detailContent);
            if (!plainText.isBlank()) {
                String desc = plainText.length() > MAX_DETAIL_LENGTH
                        ? plainText.substring(0, MAX_DETAIL_LENGTH) + "..."
                        : plainText;
                sb.append("\n").append(TelegramVolumeFormatter.escapeHtml(desc)).append("\n");
            }
        } else {
            // Fallback: başlık
            sb.append("\n").append(TelegramVolumeFormatter.escapeHtml(item.getTitle())).append("\n");
        }

        return sb.toString();
    }

    /**
     * Haber item'indan sirket/hisse kodunu cikarir.
     *
     * @param item KAP haber DTO'su
     * @return hisse kodu (orn: "AKFGY") veya null
     */
    private String extractCompanyCode(KapNewsItemDto item) {
        if (item.getRelatedSymbols() != null && !item.getRelatedSymbols().isEmpty()) {
            for (KapRelatedSymbolDto symbol : item.getRelatedSymbols()) {
                String s = symbol.getSymbol();
                if (s != null && !s.isEmpty()) {
                    // "BIST:AKFGY" → "AKFGY"
                    int idx = s.indexOf(':');
                    return idx >= 0 ? s.substring(idx + 1) : s;
                }
            }
        }
        return null;
    }

    /**
     * HTML icerigini duz metne donusturur.
     *
     * <p>Jsoup kullanarak HTML tag'lerini cikarir,
     * tablo satirlarini satir sonu ile ayirir.</p>
     *
     * @param html HTML icerik
     * @return duz metin
     */
    private String htmlToPlainText(String html) {
        if (html == null) return "";
        try {
            // Jsoup: HTML → düz metin, <br> ve <p> → newline
            var doc = Jsoup.parse(html);
            // Tablo satırları arasına newline ekle
            doc.select("tr").append("\n");
            doc.select("td").append(" | ");
            doc.select("p").append("\n");
            doc.select("br").append("\n");

            String text = doc.text();
            // Çoklu boşlukları temizle
            text = text.replaceAll("\\| *\\|", "|")
                       .replaceAll(" *\\| *$", "")
                       .replaceAll("(?m)^\\s*\\|\\s*", "")
                       .replaceAll("\n{3,}", "\n\n")
                       .trim();
            return text;
        } catch (Exception e) {
            // Fallback: tag'leri sil
            return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        }
    }
}
