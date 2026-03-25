package com.scyborsa.api.service.telegram.infographic;

import java.awt.Graphics2D;

/**
 * Bölüm çizim yardımcı sınıfı.
 *
 * <p>Tüm bölümler tarafından ortaklaşa kullanılan arka plan çizim
 * işlemlerini içerir.</p>
 */
final class SectionDrawHelper {

    private SectionDrawHelper() {
        // Utility class — instantiation engellendi
    }

    /**
     * Yuvarlak köşeli bölüm arka planı çizer.
     *
     * @param g      çizim bağlamı
     * @param x      sol üst köşe x koordinatı
     * @param y      sol üst köşe y koordinatı
     * @param width  genişlik (piksel)
     * @param height yükseklik (piksel)
     */
    static void drawSectionBackground(Graphics2D g, int x, int y, int width, int height) {
        g.setColor(CardTheme.CARD_BG);
        g.fillRoundRect(x, y, width, height, CardTheme.CORNER_RADIUS, CardTheme.CORNER_RADIUS);
    }

    /**
     * Bölümler arası ince ayırıcı çizgi çizer.
     *
     * @param g     çizim bağlamı
     * @param x     başlangıç x koordinatı
     * @param y     çizgi y koordinatı
     * @param width çizgi genişliği (piksel)
     */
    static void drawDividerLine(Graphics2D g, int x, int y, int width) {
        g.setColor(CardTheme.DIVIDER);
        g.drawLine(x, y, x + width, y);
    }
}
