package com.scyborsa.api.service.telegram.infographic;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.RenderingHints;

/**
 * Hisse adı, fiyat ve değişim yüzdesini gösteren başlık bölümü.
 *
 * <p>Sol tarafta hisse adı (Bold 32, beyaz), sağ tarafta fiyat (CYAN Bold 28)
 * ve değişim yüzdesi (yeşil/kırmızı yuvarlak badge) yer alır. Alt kenarda
 * CYAN'dan şeffafa geçişli gradient ayırıcı çizgi çizilir.</p>
 */
public class HeaderSection implements CardSection {

    private static final int HEIGHT = 90;
    private static final int BADGE_H_PAD = 10;
    private static final int BADGE_V_PAD = 5;
    private static final int BADGE_RADIUS = 14;

    /**
     * {@inheritDoc}
     */
    @Override
    public int render(Graphics2D g, StockCardData data, int y, int width) {
        int leftX = CardTheme.PADDING;
        int rightX = width - CardTheme.PADDING;

        // --- Satır 1: Hisse adı (sol) + Değişim badge (sağ) ---
        int row1Baseline = y + 40;

        // Hisse adı (büyük, Bold)
        g.setFont(CardTheme.FONT_TITLE);
        g.setColor(CardTheme.TEXT_PRIMARY);
        g.drawString(safe(data.getStockName()), leftX, row1Baseline);

        // Değişim badge (yuvarlak dolgulu arka plan + beyaz metin)
        double change = data.getChangePercent() != null ? data.getChangePercent() : 0.0;
        Color changeColor = change >= 0 ? CardTheme.GREEN : CardTheme.RED;
        String changeText = String.format("%s%.2f%%", change >= 0 ? "+" : "", change);

        g.setFont(CardTheme.FONT_HEADER);
        FontMetrics headerFm = g.getFontMetrics();
        int changeTextWidth = headerFm.stringWidth(changeText);
        int badgeW = changeTextWidth + 2 * BADGE_H_PAD;
        int badgeH = headerFm.getHeight() + 2 * BADGE_V_PAD;
        int badgeX = rightX - badgeW;
        int badgeY = row1Baseline - headerFm.getAscent() - BADGE_V_PAD;

        Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(changeColor);
        g.fillRoundRect(badgeX, badgeY, badgeW, badgeH, BADGE_RADIUS, BADGE_RADIUS);
        g.setColor(Color.WHITE);
        g.drawString(changeText, badgeX + BADGE_H_PAD, row1Baseline);
        if (oldAA != null) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA);
        }

        // --- Satır 2: Fiyat (sol, CYAN, büyük) ---
        int row2Baseline = row1Baseline + 36;
        g.setFont(CardTheme.FONT_PRICE);
        String priceText = data.getPrice() != null
                ? String.format("%.2f TL", data.getPrice())
                : "— TL";
        g.setColor(CardTheme.CYAN);
        g.drawString(priceText, leftX, row2Baseline);

        // Alt gradient ayırıcı çizgi (CYAN → şeffaf)
        int lineY = y + HEIGHT - 4;
        Paint oldPaint = g.getPaint();
        g.setPaint(new GradientPaint(
                leftX, lineY, CardTheme.CYAN,
                rightX, lineY, new Color(0x00, 0xD4, 0xFF, 0)
        ));
        g.fillRect(leftX, lineY, rightX - leftX, 2);
        g.setPaint(oldPaint);

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
     * Null-safe string dönüşümü.
     *
     * @param value metin değeri
     * @return değer veya boş string
     */
    private String safe(String value) {
        return value != null ? value : "";
    }
}
