package com.scyborsa.api.service.telegram;

import com.scyborsa.api.dto.enrichment.BrokerageAkdListResponseDto;
import com.scyborsa.api.dto.enrichment.BrokerageAkdListResponseDto.BrokerageAkdItemDto;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AKD Piyasa Özeti Telegram mesaj builder'ı.
 *
 * <p>Top 5 alıcı ve top 5 satıcı kurumların net hacim verilerini
 * HTML formatında Telegram mesajına dönüştürür.</p>
 *
 * @see com.scyborsa.api.service.job.AkdPiyasaOzetiJob
 * @see com.scyborsa.api.service.BrokerageAkdListService
 */
@Component
public class AkdPiyasaOzetiTelegramBuilder {

    /** Gosterilecek maksimum kurum sayisi (alici ve satici icin ayri). */
    private static final int TOP_COUNT = 5;

    /** Saat formatlayici (HH:mm). */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * AKD listesinden Telegram mesajı oluşturur.
     *
     * @param response AKD listesi response DTO'su
     * @return HTML formatında Telegram mesajı, veri yoksa null
     */
    public String build(BrokerageAkdListResponseDto response) {
        if (response == null || response.getItems() == null || response.getItems().isEmpty()) {
            return null;
        }

        List<BrokerageAkdItemDto> items = response.getItems();

        List<BrokerageAkdItemDto> topBuyers = items.stream()
                .filter(i -> i.getNetVolume() > 0)
                .sorted(Comparator.comparingLong(BrokerageAkdItemDto::getNetVolume).reversed())
                .limit(TOP_COUNT)
                .collect(Collectors.toList());

        List<BrokerageAkdItemDto> topSellers = items.stream()
                .filter(i -> i.getNetVolume() < 0)
                .sorted(Comparator.comparingLong(BrokerageAkdItemDto::getNetVolume))
                .limit(TOP_COUNT)
                .collect(Collectors.toList());

        if (topBuyers.isEmpty() && topSellers.isEmpty()) {
            return null;
        }

        long buyerCount = items.stream().filter(i -> i.getNetVolume() > 0).count();
        long sellerCount = items.stream().filter(i -> i.getNetVolume() < 0).count();

        var sb = new StringBuilder();
        sb.append("🏦 <b>PİYASA KURUMSAL ÖZET</b> | ").append(LocalTime.now().format(TIME_FMT)).append("\n\n");

        if (!topBuyers.isEmpty()) {
            sb.append("━━━━ 🟢 <b>EN ÇOK ALAN KURUMLAR</b> ━━━━\n");
            for (int i = 0; i < topBuyers.size(); i++) {
                BrokerageAkdItemDto item = topBuyers.get(i);
                sb.append(i + 1).append(". ")
                        .append(TelegramVolumeFormatter.escapeHtml(item.getShortTitle()))
                        .append(": <code>").append(TelegramVolumeFormatter.formatVolumeTurkish(item.getNetVolume()))
                        .append("</code> ₺\n");
            }
            sb.append("\n");
        }

        if (!topSellers.isEmpty()) {
            sb.append("━━━━ 🔴 <b>EN ÇOK SATAN KURUMLAR</b> ━━━━\n");
            for (int i = 0; i < topSellers.size(); i++) {
                BrokerageAkdItemDto item = topSellers.get(i);
                sb.append(i + 1).append(". ")
                        .append(TelegramVolumeFormatter.escapeHtml(item.getShortTitle()))
                        .append(": <code>").append(TelegramVolumeFormatter.formatVolumeTurkish(item.getNetVolume()))
                        .append("</code> ₺\n");
            }
            sb.append("\n");
        }

        sb.append("📊 Piyasa: ").append(buyerCount).append(" alıcı | ")
                .append(sellerCount).append(" satıcı kurum\n");
        sb.append("🤖 <i>ScyBorsa Bot</i>");

        return sb.toString();
    }
}
