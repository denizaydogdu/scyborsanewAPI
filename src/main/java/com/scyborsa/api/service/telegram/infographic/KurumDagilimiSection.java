package com.scyborsa.api.service.telegram.infographic;

import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.List;

/**
 * Kurum dağılımı bölümü (alıcı ve satıcı kurumlar).
 *
 * <p>İki sütunlu düzende sol tarafta alıcı kurumlar (yeşil nokta),
 * sağ tarafta satıcı kurumlar (kırmızı nokta) gösterilir.
 * Her sütunda en fazla 3 kurum listelenir.</p>
 */
public class KurumDagilimiSection implements CardSection {

    private static final int TITLE_HEIGHT = 34;
    private static final int MAX_ROWS = 3;
    private static final int DOT_SIZE = 8;
    private static final int DOT_TEXT_GAP = 6;
    private static final int COLUMN_GAP = 20;
    private static final int ICON_SIZE = 8;

    /**
     * {@inheritDoc}
     */
    @Override
    public int render(Graphics2D g, StockCardData data, int y, int width) {
        List<StockCardData.KurumItem> alicilar = data.getAliciKurumlar();
        List<StockCardData.KurumItem> saticilar = data.getSaticiKurumlar();

        if (isEmpty(alicilar) && isEmpty(saticilar)) {
            return 0;
        }

        int totalHeight = measureHeight(data);
        int leftX = CardTheme.PADDING + CardTheme.SECTION_PADDING;
        int rightX = width - CardTheme.PADDING - CardTheme.SECTION_PADDING;
        int midX = leftX + (rightX - leftX) / 2;

        // Bölüm arka planı
        SectionDrawHelper.drawSectionBackground(g, CardTheme.PADDING, y, width - 2 * CardTheme.PADDING, totalHeight);

        y += CardTheme.SECTION_PADDING;

        // Başlık
        g.setColor(CardTheme.CYAN);
        g.fillRect(leftX, y + 8, ICON_SIZE, ICON_SIZE);
        g.setFont(CardTheme.FONT_HEADER);
        g.drawString("KURUM DAĞILIMI", leftX + ICON_SIZE + 8, y + TITLE_HEIGHT - 12);
        y += TITLE_HEIGHT;

        g.setFont(CardTheme.FONT_BODY);
        FontMetrics fm = g.getFontMetrics();

        // Sol sütun: Alıcılar
        int aliciRows = alicilar != null ? Math.min(alicilar.size(), MAX_ROWS) : 0;
        for (int i = 0; i < aliciRows; i++) {
            StockCardData.KurumItem item = alicilar.get(i);
            int rowY = y + (i * CardTheme.ROW_HEIGHT);
            int textY = rowY + fm.getAscent();

            // Yeşil nokta
            g.setColor(CardTheme.GREEN);
            g.fillOval(leftX, rowY + (CardTheme.ROW_HEIGHT - DOT_SIZE) / 2, DOT_SIZE, DOT_SIZE);

            // Kurum adı
            g.setColor(CardTheme.TEXT_PRIMARY);
            g.drawString(safe(item.getKurumAdi()), leftX + DOT_SIZE + DOT_TEXT_GAP, textY);

            // Hacim (sütun ortasına yakın sağda)
            g.setColor(CardTheme.GREEN);
            String vol = safe(item.getFormattedVolume());
            int volWidth = fm.stringWidth(vol);
            g.drawString(vol, midX - COLUMN_GAP - volWidth, textY);
        }

        // Sağ sütun: Satıcılar
        int saticiRows = saticilar != null ? Math.min(saticilar.size(), MAX_ROWS) : 0;
        for (int i = 0; i < saticiRows; i++) {
            StockCardData.KurumItem item = saticilar.get(i);
            int rowY = y + (i * CardTheme.ROW_HEIGHT);
            int textY = rowY + fm.getAscent();

            // Kırmızı nokta
            g.setColor(CardTheme.RED);
            g.fillOval(midX + COLUMN_GAP, rowY + (CardTheme.ROW_HEIGHT - DOT_SIZE) / 2, DOT_SIZE, DOT_SIZE);

            // Kurum adı
            g.setColor(CardTheme.TEXT_PRIMARY);
            g.drawString(safe(item.getKurumAdi()), midX + COLUMN_GAP + DOT_SIZE + DOT_TEXT_GAP, textY);

            // Hacim
            g.setColor(CardTheme.RED);
            String vol = safe(item.getFormattedVolume());
            int volWidth = fm.stringWidth(vol);
            g.drawString(vol, rightX - volWidth, textY);
        }

        return totalHeight;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int measureHeight(StockCardData data) {
        List<StockCardData.KurumItem> alicilar = data.getAliciKurumlar();
        List<StockCardData.KurumItem> saticilar = data.getSaticiKurumlar();

        if (isEmpty(alicilar) && isEmpty(saticilar)) {
            return 0;
        }

        int aliciRows = alicilar != null ? Math.min(alicilar.size(), MAX_ROWS) : 0;
        int saticiRows = saticilar != null ? Math.min(saticilar.size(), MAX_ROWS) : 0;
        int maxRows = Math.max(aliciRows, saticiRows);

        return 2 * CardTheme.SECTION_PADDING + TITLE_HEIGHT + maxRows * CardTheme.ROW_HEIGHT;
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
     * Null-safe string dönüşümü.
     *
     * @param value metin değeri
     * @return değer veya boş string
     */
    private String safe(String value) {
        return value != null ? value : "";
    }
}
