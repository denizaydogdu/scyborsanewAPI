package com.scyborsa.api.service.telegram;

import com.scyborsa.api.dto.sector.SectorSummaryDto;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Sektör Özeti Telegram mesaj builder'ı.
 *
 * <p>En çok yükselen ve düşen sektörleri HTML formatında
 * Telegram mesajına dönüştürür.</p>
 *
 * @see SektorTelegramJob
 * @see com.scyborsa.api.service.SectorService
 */
@Component
public class SektorOzetiTelegramBuilder {

    private static final int TOP_COUNT = 5;
    private static final DateTimeFormatter DATETIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm - dd MMMM yyyy", new Locale("tr"));

    /**
     * Sektör listesinden Telegram mesajı oluşturur.
     *
     * @param summaries sektör özet listesi (avgChangePercent desc sıralı)
     * @return HTML formatında Telegram mesajı, veri yoksa null
     */
    public String build(List<SectorSummaryDto> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return null;
        }

        List<SectorSummaryDto> rising = summaries.stream()
                .filter(s -> s.getAvgChangePercent() > 0)
                .limit(TOP_COUNT)
                .toList();

        List<SectorSummaryDto> falling = summaries.stream()
                .filter(s -> s.getAvgChangePercent() < 0)
                .sorted((a, b) -> Double.compare(a.getAvgChangePercent(), b.getAvgChangePercent()))
                .limit(TOP_COUNT)
                .toList();

        if (rising.isEmpty() && falling.isEmpty()) {
            return null;
        }

        var sb = new StringBuilder();
        sb.append("📊 <b>BIST SEKTÖR ÖZETİ</b>\n");
        sb.append("🕐 ").append(LocalDateTime.now().format(DATETIME_FMT)).append("\n\n");

        if (!rising.isEmpty()) {
            sb.append("🟢 EN ÇOK YÜKSELEN SEKTÖRLER\n");
            for (int i = 0; i < rising.size(); i++) {
                SectorSummaryDto s = rising.get(i);
                sb.append(i + 1).append(". ")
                        .append(TelegramVolumeFormatter.escapeHtml(s.getDisplayName()))
                        .append("  ").append(String.format("%+.2f%%", s.getAvgChangePercent()))
                        .append("  (").append(s.getStockCount()).append(" hisse)")
                        .append("\n");
            }
            sb.append("\n");
        }

        if (!falling.isEmpty()) {
            sb.append("🔴 EN ÇOK DÜŞEN SEKTÖRLER\n");
            for (int i = 0; i < falling.size(); i++) {
                SectorSummaryDto s = falling.get(i);
                sb.append(i + 1).append(". ")
                        .append(TelegramVolumeFormatter.escapeHtml(s.getDisplayName()))
                        .append("  ").append(String.format("%+.2f%%", s.getAvgChangePercent()))
                        .append("  (").append(s.getStockCount()).append(" hisse)")
                        .append("\n");
            }
            sb.append("\n");
        }

        sb.append("🤖 ScyBorsa Bot");
        return sb.toString();
    }
}
