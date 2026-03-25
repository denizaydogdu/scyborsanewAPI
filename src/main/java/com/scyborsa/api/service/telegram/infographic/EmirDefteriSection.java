package com.scyborsa.api.service.telegram.infographic;

import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.List;

/**
 * Emir defteri bölümü (alış ve satış emirleri).
 *
 * <p>Alış emirleri yeşil nokta, satış emirleri kırmızı nokta ile işaretlenir.
 * Her satırda zaman, fiyat, lot ve kurum bilgisi (gönderen → hedef) gösterilir.
 * Alış ve satış arasında ince bir ayırıcı çizgi çizilir.</p>
 */
public class EmirDefteriSection implements CardSection {

    private static final int TITLE_HEIGHT = 34;
    private static final int DIVIDER_HEIGHT = 8;
    private static final int DOT_SIZE = 8;
    private static final int ICON_SIZE = 8;

    /**
     * {@inheritDoc}
     */
    @Override
    public int render(Graphics2D g, StockCardData data, int y, int width) {
        List<StockCardData.EmirItem> alis = data.getAlisEmirler();
        List<StockCardData.EmirItem> satis = data.getSatisEmirler();

        if (isEmpty(alis) && isEmpty(satis)) {
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
        g.drawString("EMİR DEFTERİ", leftX + ICON_SIZE + 8, y + TITLE_HEIGHT - 12);
        y += TITLE_HEIGHT;

        g.setFont(CardTheme.FONT_BODY);
        FontMetrics fm = g.getFontMetrics();

        // Sütun pozisyonları
        int timeCol = leftX + DOT_SIZE + 8;
        int priceCol = timeCol + 80;
        int lotCol = priceCol + 100;
        int fromToCol = lotCol + 70;
        int maxFromToWidth = rightX - fromToCol;

        // Alış emirleri
        int alisCount = alis != null ? alis.size() : 0;
        for (int i = 0; i < alisCount; i++) {
            StockCardData.EmirItem item = alis.get(i);
            int rowY = y + (i * CardTheme.ROW_HEIGHT);
            int textY = rowY + fm.getAscent();

            // Yeşil nokta
            g.setColor(CardTheme.GREEN);
            g.fillOval(leftX, rowY + (CardTheme.ROW_HEIGHT - DOT_SIZE) / 2, DOT_SIZE, DOT_SIZE);

            // Zaman
            g.setColor(CardTheme.TEXT_SECONDARY);
            g.drawString(safe(item.getTime()), timeCol, textY);

            // Fiyat
            g.setColor(CardTheme.GREEN);
            g.drawString(safe(item.getPrice()), priceCol, textY);

            // Lot
            g.setColor(CardTheme.TEXT_PRIMARY);
            g.drawString(safe(item.getLot()), lotCol, textY);

            // Gönderen → Hedef (taşma korumalı)
            g.setColor(CardTheme.TEXT_SECONDARY);
            String fromTo = safe(item.getFrom()) + " → " + safe(item.getTo());
            fromTo = truncateToFit(fromTo, fm, maxFromToWidth);
            g.drawString(fromTo, fromToCol, textY);
        }

        y += alisCount * CardTheme.ROW_HEIGHT;

        // Ayırıcı çizgi (alış ve satış arası)
        if (alisCount > 0 && satis != null && !satis.isEmpty()) {
            int dividerY = y + DIVIDER_HEIGHT / 2;
            g.setColor(CardTheme.DIVIDER);
            g.drawLine(leftX, dividerY, rightX, dividerY);
            y += DIVIDER_HEIGHT;
        }

        // Satış emirleri
        int satisCount = satis != null ? satis.size() : 0;
        for (int i = 0; i < satisCount; i++) {
            StockCardData.EmirItem item = satis.get(i);
            int rowY = y + (i * CardTheme.ROW_HEIGHT);
            int textY = rowY + fm.getAscent();

            // Kırmızı nokta
            g.setColor(CardTheme.RED);
            g.fillOval(leftX, rowY + (CardTheme.ROW_HEIGHT - DOT_SIZE) / 2, DOT_SIZE, DOT_SIZE);

            // Zaman
            g.setColor(CardTheme.TEXT_SECONDARY);
            g.drawString(safe(item.getTime()), timeCol, textY);

            // Fiyat
            g.setColor(CardTheme.RED);
            g.drawString(safe(item.getPrice()), priceCol, textY);

            // Lot
            g.setColor(CardTheme.TEXT_PRIMARY);
            g.drawString(safe(item.getLot()), lotCol, textY);

            // Gönderen → Hedef (taşma korumalı)
            g.setColor(CardTheme.TEXT_SECONDARY);
            String fromTo = safe(item.getFrom()) + " → " + safe(item.getTo());
            fromTo = truncateToFit(fromTo, fm, maxFromToWidth);
            g.drawString(fromTo, fromToCol, textY);
        }

        return totalHeight;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int measureHeight(StockCardData data) {
        List<StockCardData.EmirItem> alis = data.getAlisEmirler();
        List<StockCardData.EmirItem> satis = data.getSatisEmirler();

        if (isEmpty(alis) && isEmpty(satis)) {
            return 0;
        }

        int alisCount = alis != null ? alis.size() : 0;
        int satisCount = satis != null ? satis.size() : 0;
        int divider = (alisCount > 0 && satisCount > 0) ? DIVIDER_HEIGHT : 0;

        return 2 * CardTheme.SECTION_PADDING + TITLE_HEIGHT
                + alisCount * CardTheme.ROW_HEIGHT
                + divider
                + satisCount * CardTheme.ROW_HEIGHT;
    }

    /**
     * Liste boş mu kontrolü.
     *
     * @param list kontrol edilecek liste
     * @return liste null veya boş ise true
     */
    private boolean isEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }

    /**
     * Metni belirtilen piksel genişliğine sığacak şekilde kısaltır.
     *
     * @param text     orijinal metin
     * @param fm       aktif font ölçüleri
     * @param maxWidth izin verilen maksimum genişlik (piksel)
     * @return sığan metin veya "..." ile kısaltılmış hali
     */
    private String truncateToFit(String text, FontMetrics fm, int maxWidth) {
        if (fm.stringWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        int ellipsisWidth = fm.stringWidth(ellipsis);
        for (int i = text.length() - 1; i > 0; i--) {
            if (fm.stringWidth(text.substring(0, i)) + ellipsisWidth <= maxWidth) {
                return text.substring(0, i) + ellipsis;
            }
        }
        return ellipsis;
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
