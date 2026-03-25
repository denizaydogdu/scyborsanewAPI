package com.scyborsa.api.service.telegram.infographic;

import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.List;

/**
 * Fon pozisyonları bölümü.
 *
 * <p>Üst 3 fon pozisyonunu tablo formatında gösterir: fon kodu (sol),
 * lot miktarı (orta), ağırlık yüzdesi (sağ). Ek fonlar varsa
 * "... ve N fon daha" satırı eklenir.</p>
 */
public class FonPozisyonSection implements CardSection {

    private static final int TITLE_HEIGHT = 34;
    private static final int EXTRA_LINE_HEIGHT = 20;
    private static final int MAX_ROWS = 3;
    private static final int ICON_SIZE = 8;

    /**
     * {@inheritDoc}
     */
    @Override
    public int render(Graphics2D g, StockCardData data, int y, int width) {
        List<StockCardData.FonPozisyonItem> items = data.getFonPozisyonlari();
        if (items == null || items.isEmpty()) {
            return 0;
        }

        int startY = y;
        int leftX = CardTheme.PADDING + CardTheme.SECTION_PADDING;
        int rightX = width - CardTheme.PADDING - CardTheme.SECTION_PADDING;
        int contentWidth = rightX - leftX;

        // Bölüm arka planı
        int totalHeight = measureHeight(data);
        SectionDrawHelper.drawSectionBackground(g, CardTheme.PADDING, y, width - 2 * CardTheme.PADDING, totalHeight);

        y += CardTheme.SECTION_PADDING;

        // Başlık: küçük CYAN kare + "FON POZİSYONLARI"
        g.setColor(CardTheme.CYAN);
        g.fillRect(leftX, y + 8, ICON_SIZE, ICON_SIZE);

        g.setFont(CardTheme.FONT_HEADER);
        g.setColor(CardTheme.CYAN);
        g.drawString("FON POZİSYONLARI", leftX + ICON_SIZE + 8, y + TITLE_HEIGHT - 12);
        y += TITLE_HEIGHT;

        // Satırlar
        g.setFont(CardTheme.FONT_BODY);
        FontMetrics fm = g.getFontMetrics();
        int rowCount = Math.min(items.size(), MAX_ROWS);

        for (int i = 0; i < rowCount; i++) {
            StockCardData.FonPozisyonItem item = items.get(i);
            int rowY = y + (i * CardTheme.ROW_HEIGHT) + fm.getAscent();

            // Fon kodu (sol)
            g.setColor(CardTheme.TEXT_PRIMARY);
            g.drawString(safe(item.getFonKodu()), leftX, rowY);

            // Lot (orta)
            String lotText = safe(item.getLotFormatted());
            int lotWidth = fm.stringWidth(lotText);
            g.setColor(CardTheme.TEXT_SECONDARY);
            g.drawString(lotText, leftX + contentWidth / 2 - lotWidth / 2, rowY);

            // Ağırlık (sağ)
            String agirlikText = safe(item.getAgirlik());
            int agirlikWidth = fm.stringWidth(agirlikText);
            g.setColor(CardTheme.CYAN);
            g.drawString(agirlikText, rightX - agirlikWidth, rowY);
        }

        y += rowCount * CardTheme.ROW_HEIGHT;

        // Ek fon satırı
        if (data.getExtraFonCount() > 0) {
            g.setFont(CardTheme.FONT_SMALL);
            g.setColor(CardTheme.TEXT_SECONDARY);
            g.drawString("... ve " + data.getExtraFonCount() + " fon daha",
                    leftX, y + g.getFontMetrics().getAscent());
        }

        return totalHeight;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int measureHeight(StockCardData data) {
        List<StockCardData.FonPozisyonItem> items = data.getFonPozisyonlari();
        if (items == null || items.isEmpty()) {
            return 0;
        }

        int rows = Math.min(items.size(), MAX_ROWS);
        int height = 2 * CardTheme.SECTION_PADDING + TITLE_HEIGHT + rows * CardTheme.ROW_HEIGHT;
        if (data.getExtraFonCount() > 0) {
            height += EXTRA_LINE_HEIGHT;
        }
        return height;
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
