package com.scyborsa.api.service.telegram;

import com.scyborsa.api.dto.screener.TvScreenerResponse;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Piyasa Özeti Telegram mesaj builder'ı.
 *
 * <p>En çok yükselen, düşen ve hacimli hisseleri HTML formatında
 * Telegram mesajına dönüştürür.</p>
 *
 * @see com.scyborsa.api.service.MarketSummaryTelegramJob
 */
@Component
public class MarketSummaryTelegramBuilder {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final int TOP_LIMIT = 5;

    /**
     * 3 tarama sonucundan Telegram mesajı oluşturur.
     *
     * @param rising  yükselen hisseler tarama sonucu
     * @param falling düşen hisseler tarama sonucu
     * @param volume  hacimli hisseler tarama sonucu
     * @return HTML formatında Telegram mesajı, tüm veriler null ise null
     */
    public String build(TvScreenerResponse rising, TvScreenerResponse falling, TvScreenerResponse volume) {
        if (isEmpty(rising) && isEmpty(falling) && isEmpty(volume)) {
            return null;
        }

        var sb = new StringBuilder();
        sb.append("\uD83D\uDCCA <b>BIST PİYASA ÖZETİ</b> | ").append(LocalTime.now().format(TIME_FMT)).append("\n\n");

        // Columns order in scan body: name(0), description(1), close(2), change(3), volume(4)
        if (!isEmpty(rising)) {
            sb.append("━━━━ \uD83D\uDFE2 EN ÇOK YÜKSELEN ━━━━\n");
            appendStockLines(sb, rising.getData());
            sb.append("\n");
        }

        if (!isEmpty(falling)) {
            sb.append("━━━━ \uD83D\uDD34 EN ÇOK DÜŞEN ━━━━\n");
            appendStockLines(sb, falling.getData());
            sb.append("\n");
        }

        if (!isEmpty(volume)) {
            sb.append("━━━━ \uD83D\uDCCA EN YÜKSEK HACİMLİ ━━━━\n");
            appendStockLines(sb, volume.getData());
            sb.append("\n");
        }

        sb.append("\uD83E\uDD16 <i>ScyBorsa Bot</i>");
        return sb.toString();
    }

    /**
     * Hisse satırlarını mesaja ekler.
     *
     * @param sb   StringBuilder
     * @param data tarama sonuç verileri
     */
    private void appendStockLines(StringBuilder sb, List<TvScreenerResponse.DataItem> data) {
        int displayNum = 0;
        for (var item : data) {
            List<Object> d = item.getD();
            if (d == null || d.size() < 5) continue;

            displayNum++;
            if (displayNum > TOP_LIMIT) break;
            String ticker = extractTicker(item.getS());
            double close = toDouble(d.get(2));
            double change = toDouble(d.get(3));
            double vol = toDouble(d.get(4));

            sb.append(displayNum).append(". ")
                    .append(TelegramVolumeFormatter.escapeHtml(ticker))
                    .append("  ").append(String.format("%+.2f%%", change))
                    .append("  ").append(String.format("%.2f₺", close))
                    .append("  \uD83D\uDCCA ").append(TelegramVolumeFormatter.formatVolumeTurkish(vol))
                    .append("\n");
        }
    }

    /**
     * "BIST:THYAO" formatından ticker'ı çıkarır.
     *
     * @param symbol tam sembol (ör: "BIST:THYAO")
     * @return ticker (ör: "THYAO")
     */
    private String extractTicker(String symbol) {
        if (symbol == null) return "";
        int idx = symbol.indexOf(':');
        return idx >= 0 ? symbol.substring(idx + 1) : symbol;
    }

    private boolean isEmpty(TvScreenerResponse response) {
        return response == null || response.getData() == null || response.getData().isEmpty();
    }

    private double toDouble(Object val) {
        if (val instanceof Number) return ((Number) val).doubleValue();
        if (val instanceof String) {
            try { return Double.parseDouble((String) val); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }
}
