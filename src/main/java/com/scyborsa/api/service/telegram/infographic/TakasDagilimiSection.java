package com.scyborsa.api.service.telegram.infographic;

import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.List;

/**
 * Takas dağılımı bölümü.
 *
 * <p>Saklama kuruluşlarını yatay çubuk grafik ile gösterir.
 * Her satırda kuruluş kodu (sol), formatlanmış değer (orta),
 * orantılı çubuk ve yüzde değeri (sağ) yer alır.
 * En fazla 5 satır gösterilir.</p>
 */
public class TakasDagilimiSection implements CardSection {

    private static final int TITLE_HEIGHT = 34;
    private static final int MAX_ROWS = 5;
    private static final int BAR_HEIGHT = 14;
    private static final int BAR_RADIUS = 7;
    private static final int ICON_SIZE = 8;

    /**
     * {@inheritDoc}
     */
    @Override
    public int render(Graphics2D g, StockCardData data, int y, int width) {
        List<StockCardData.TakasItem> items = data.getTakasDagilimi();
        if (items == null || items.isEmpty()) {
            return 0;
        }

        int totalHeight = measureHeight(data);
        int leftX = CardTheme.PADDING + CardTheme.SECTION_PADDING;
        int rightX = width - CardTheme.PADDING - CardTheme.SECTION_PADDING;

        // Bölüm arka planı
        SectionDrawHelper.drawSectionBackground(g, CardTheme.PADDING, y, width - 2 * CardTheme.PADDING, totalHeight);

        y += CardTheme.SECTION_PADDING;

        // Başlık
        g.setColor(CardTheme.CYAN);
        g.fillRect(leftX, y + 8, ICON_SIZE, ICON_SIZE);
        g.setFont(CardTheme.FONT_HEADER);
        g.drawString("TAKAS DAĞILIMI", leftX + ICON_SIZE + 8, y + TITLE_HEIGHT - 12);
        y += TITLE_HEIGHT;

        g.setFont(CardTheme.FONT_BODY);
        FontMetrics fm = g.getFontMetrics();

        int rowCount = Math.min(items.size(), MAX_ROWS);
        // Sütun pozisyonları (orantılı çubuk genişliği)
        int codeColWidth = 70;
        int valueColEnd = leftX + codeColWidth + 140;
        int barStartX = valueColEnd + 12;
        int percentTextWidth = fm.stringWidth("100.0%") + 10;
        int barMaxWidth = rightX - barStartX - percentTextWidth;
        int percentStartX = barStartX + barMaxWidth + 10;

        for (int i = 0; i < rowCount; i++) {
            StockCardData.TakasItem item = items.get(i);
            int rowY = y + (i * CardTheme.ROW_HEIGHT);
            int textY = rowY + fm.getAscent();
            int barY = rowY + (CardTheme.ROW_HEIGHT - BAR_HEIGHT) / 2;

            // Kuruluş kodu
            g.setColor(CardTheme.TEXT_PRIMARY);
            g.drawString(safe(item.getCustodianCode()), leftX, textY);

            // Formatlanmış değer
            g.setColor(CardTheme.TEXT_SECONDARY);
            g.drawString(safe(item.getFormattedValue()), leftX + codeColWidth, textY);

            // Yatay çubuk (orantılı genişlik, CYAN, yuvarlak uçlu)
            double pct = Math.min(item.getPercentage(), 1.0);
            int barWidth = Math.max(4, (int) (barMaxWidth * pct));
            g.setColor(CardTheme.CYAN);
            g.fillRoundRect(barStartX, barY, barWidth, BAR_HEIGHT, BAR_RADIUS, BAR_RADIUS);

            // Yüzde metni
            g.setColor(CardTheme.CYAN);
            String pctText = String.format("%.1f%%", item.getPercentage() * 100);
            g.drawString(pctText, percentStartX, textY);
        }

        return totalHeight;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int measureHeight(StockCardData data) {
        List<StockCardData.TakasItem> items = data.getTakasDagilimi();
        if (items == null || items.isEmpty()) {
            return 0;
        }

        int rows = Math.min(items.size(), MAX_ROWS);
        return 2 * CardTheme.SECTION_PADDING + TITLE_HEIGHT + rows * CardTheme.ROW_HEIGHT;
    }

    /**
     * Null-safe string dönüşümü.
     *
     * @param value metin değeri
     * @return değer veya boş string
     */
    private String safe(String value) {
        return value != null ? value : "";
    }
}
