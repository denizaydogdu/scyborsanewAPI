package com.scyborsa.api.service.telegram.infographic;

import java.awt.FontMetrics;
import java.awt.Graphics2D;

/**
 * Kart alt bilgi bölümü (tarama özeti, zaman damgası, telif hakkı).
 *
 * <p>Üç satırdan oluşur:
 * <ol>
 *   <li>Tarama sayısı, ilk/son sinyal zamanı</li>
 *   <li>Zaman damgası</li>
 *   <li>"ScyBorsa Bot" telif hakkı (sağ hizalı, CYAN)</li>
 * </ol>
 * Üst kenarda ince ayırıcı çizgi çizilir.</p>
 */
public class FooterSection implements CardSection {

    private static final int HEIGHT = 78;
    private static final int LINE_SPACING = 20;

    /**
     * {@inheritDoc}
     */
    @Override
    public int render(Graphics2D g, StockCardData data, int y, int width) {
        int leftX = CardTheme.PADDING;
        int rightX = width - CardTheme.PADDING;

        // Üst ayırıcı çizgi
        g.setColor(CardTheme.DIVIDER);
        g.drawLine(leftX, y + 4, rightX, y + 4);

        int textY = y + 20;

        g.setFont(CardTheme.FONT_SMALL);
        FontMetrics fm = g.getFontMetrics();

        // Satır 1: Tarama bilgisi
        g.setColor(CardTheme.TEXT_SECONDARY);
        String line1 = buildScreenerLine(data);
        g.drawString(line1, leftX, textY);
        textY += LINE_SPACING;

        // Satır 2: Zaman damgası
        String line2 = safe(data.getTimestamp());
        g.drawString(line2, leftX, textY);
        textY += LINE_SPACING;

        // Satır 3: Telif hakkı (sağ hizalı)
        g.setColor(CardTheme.CYAN);
        String copyright = "\u00A9 ScyBorsa Bot";
        int copyrightWidth = fm.stringWidth(copyright);
        g.drawString(copyright, rightX - copyrightWidth, textY);

        return HEIGHT;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int measureHeight(StockCardData data) {
        return HEIGHT;
    }

    /**
     * Tarama bilgi satırını oluşturur.
     *
     * @param data hisse kartı verisi
     * @return formatlanmış tarama bilgi metni
     */
    private String buildScreenerLine(StockCardData data) {
        StringBuilder sb = new StringBuilder();
        sb.append(data.getScreenerCount()).append(" ortak tarama");

        if (data.getFirstSignalTime() != null) {
            sb.append(" | İlk: ").append(data.getFirstSignalTime());
        }
        if (data.getLastSignalTime() != null) {
            sb.append(" | Son: ").append(data.getLastSignalTime());
        }

        return sb.toString();
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
