package com.scyborsa.api.service.telegram;

import com.scyborsa.api.dto.kap.KapNewsItemDto;
import com.scyborsa.api.dto.kap.KapRelatedSymbolDto;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * KAP Haber Telegram mesaj builder'i.
 *
 * <p>Her KAP haberi icin ayri bir HTML mesaj olusturur.
 * Ilgili semboller ve kaynak bilgisi eklenir.</p>
 *
 * @see com.scyborsa.api.service.job.KapHaberTelegramJob
 * @see com.scyborsa.api.service.kap.FintablesNewsService
 */
@Component
public class KapHaberTelegramBuilder {

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
     * @param item             KAP haber DTO'su
     * @param shortDescription opsiyonel kisa aciklama (HaberDetay'dan)
     * @return HTML formatinda Telegram mesaji, item null ise null
     */
    public String build(KapNewsItemDto item, String shortDescription) {
        if (item == null || item.getId() == null || item.getTitle() == null) {
            return null;
        }

        var sb = new StringBuilder();
        sb.append("\uD83D\uDCF0 <b>KAP B\u0130LD\u0130R\u0130M\u0130</b>\n\n");
        sb.append("<b>").append(TelegramVolumeFormatter.escapeHtml(item.getTitle())).append("</b>\n\n");

        if (shortDescription != null && !shortDescription.isBlank()) {
            String desc = shortDescription.length() > 500
                    ? shortDescription.substring(0, 500) + "..."
                    : shortDescription;
            sb.append("\u2139\uFE0F ").append(TelegramVolumeFormatter.escapeHtml(desc)).append("\n\n");
        }

        if (item.getProvider() != null && !item.getProvider().isEmpty()) {
            sb.append("\uD83D\uDCCC Kaynak: ").append(TelegramVolumeFormatter.escapeHtml(item.getProvider())).append("\n");
        }

        if (item.getRelatedSymbols() != null && !item.getRelatedSymbols().isEmpty()) {
            String symbols = item.getRelatedSymbols().stream()
                    .map(KapRelatedSymbolDto::getSymbol)
                    .filter(s -> s != null && !s.isEmpty())
                    .map(TelegramVolumeFormatter::escapeHtml)
                    .collect(Collectors.joining(", "));
            if (!symbols.isEmpty()) {
                sb.append("\uD83C\uDFF7\uFE0F \u0130lgili: ").append(symbols).append("\n");
            }
        }

        if (item.getFormattedPublished() != null && !item.getFormattedPublished().isEmpty()) {
            sb.append("\uD83D\uDD50 ").append(TelegramVolumeFormatter.escapeHtml(item.getFormattedPublished())).append("\n");
        } else {
            sb.append("\uD83D\uDD50 Tarih bilinmiyor\n");
        }

        sb.append("\n\uD83E\uDD16 <i>ScyBorsa Bot</i>");
        return sb.toString();
    }
}
