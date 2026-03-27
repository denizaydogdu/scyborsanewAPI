package com.scyborsa.api.service.telegram.infographic;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * AKD piyasa kurumsal \u00f6zet infografik kart\u0131 olu\u015fturucu.
 *
 * <p>En \u00e7ok alan ve satan arac\u0131 kurumlar\u0131 koyu temal\u0131 PNG g\u00f6r\u00fcnt\u00fc olarak render eder.
 * \u0130ki ge\u00e7i\u015fli render mimarisi kullan\u0131r: \u00f6nce toplam y\u00fckseklik hesaplan\u0131r,
 * ard\u0131ndan uygun boyutta {@link BufferedImage} olu\u015fturularak b\u00f6l\u00fcmler \u00e7izilir.</p>
 *
 * <p>Kart d\u00fczeni:</p>
 * <ol>
 *   <li>Ba\u015fl\u0131k: "P\u0130YASA KURUMSAL \u00d6ZET\u0130 | HH:mm" + gradient \u00e7izgi</li>
 *   <li>En \u00e7ok alan kurumlar b\u00f6l\u00fcm\u00fc (ye\u015fil ba\u015fl\u0131k + sat\u0131rlar)</li>
 *   <li>En \u00e7ok satan kurumlar b\u00f6l\u00fcm\u00fc (k\u0131rm\u0131z\u0131 ba\u015fl\u0131k + sat\u0131rlar)</li>
 *   <li>Footer: ay\u0131r\u0131c\u0131 \u00e7izgi + \u00f6zet + "\u00a9 ScyBorsa Bot"</li>
 * </ol>
 *
 * @see AkdSummaryCardData
 * @see CardTheme
 */
@Slf4j
@Component
public class AkdSummaryCardRenderer {

    /** AKD sat\u0131r y\u00fcksekli\u011fi (piksel) \u2014 CardTheme.ROW_HEIGHT ile kar\u0131\u015ft\u0131r\u0131lmamas\u0131 i\u00e7in farkl\u0131 isim. */
    private static final int AKD_ROW_HEIGHT = 36;

    /** B\u00f6l\u00fcm ba\u015fl\u0131k y\u00fcksekli\u011fi (piksel). */
    private static final int SECTION_HEADER_HEIGHT = 40;

    /** Ba\u015fl\u0131k b\u00f6l\u00fcm\u00fc y\u00fcksekli\u011fi (title + timestamp + gradient line). */
    private static final int HEADER_HEIGHT = 80;

    /** Footer b\u00f6l\u00fcm\u00fc y\u00fcksekli\u011fi (divider + \u00f6zet sat\u0131r\u0131 + copyright). */
    private static final int FOOTER_HEIGHT = 56;

    /**
     * NotoSans font dosyalar\u0131n\u0131 y\u00fckler.
     * Uygulama ba\u015flang\u0131c\u0131nda Spring taraf\u0131ndan otomatik \u00e7a\u011fr\u0131l\u0131r.
     */
    @PostConstruct
    public void init() {
        CardTheme.init();
        log.info("AkdSummaryCardRenderer ba\u015flat\u0131ld\u0131.");
    }

    /**
     * Verilen AKD verisi i\u00e7in PNG format\u0131nda infografik kart olu\u015fturur.
     *
     * <p>\u0130ki ge\u00e7i\u015fli render:
     * <ol>
     *   <li><strong>\u00d6l\u00e7\u00fcm:</strong> B\u00f6l\u00fcm y\u00fckseklikleri toplanarak toplam kart y\u00fcksekli\u011fi hesaplan\u0131r.</li>
     *   <li><strong>\u00c7izim:</strong> {@link BufferedImage} olu\u015fturulur, b\u00f6l\u00fcmler s\u0131rayla \u00e7izilir.</li>
     * </ol></p>
     *
     * @param data AKD \u00f6zet kart\u0131 verisi
     * @return PNG format\u0131nda bayt dizisi, render ba\u015far\u0131s\u0131z olursa {@code null}
     */
    public byte[] renderCard(AkdSummaryCardData data) {
        try {
            return doRender(data);
        } catch (Exception e) {
            log.error("[AKD-CARD] Render hatas\u0131: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * As\u0131l render i\u015flemini ger\u00e7ekle\u015ftirir.
     *
     * @param data AKD \u00f6zet kart\u0131 verisi
     * @return PNG format\u0131nda bayt dizisi
     */
    private byte[] doRender(AkdSummaryCardData data) {
        List<AkdSummaryCardData.BrokerageItem> buyers = safeList(data.getTopBuyers());
        List<AkdSummaryCardData.BrokerageItem> sellers = safeList(data.getTopSellers());

        // 1. Ge\u00e7i\u015f: Y\u00fckseklik hesaplama
        int totalHeight = 2 * CardTheme.PADDING; // \u00dcst + alt padding
        totalHeight += HEADER_HEIGHT;

        if (!buyers.isEmpty()) {
            totalHeight += CardTheme.SECTION_GAP;
            totalHeight += measureSectionHeight(buyers.size());
        }

        if (!sellers.isEmpty()) {
            totalHeight += CardTheme.SECTION_GAP;
            totalHeight += measureSectionHeight(sellers.size());
        }

        totalHeight += CardTheme.SECTION_GAP;
        totalHeight += FOOTER_HEIGHT;

        // 2. Ge\u00e7i\u015f: \u00c7izim
        BufferedImage image = new BufferedImage(
                CardTheme.WIDTH, totalHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();

        try {
            applyRenderingHints(g);
            drawBackground(g, CardTheme.WIDTH, totalHeight);

            int y = CardTheme.PADDING;

            // Ba\u015fl\u0131k
            y = drawHeader(g, data.getTimestamp(), y);

            // En \u00e7ok alan kurumlar
            if (!buyers.isEmpty()) {
                y += CardTheme.SECTION_GAP;
                y = drawBrokerageSection(g, "EN \u00c7OK ALAN KURUMLAR",
                        buyers, CardTheme.GREEN, y);
            }

            // En \u00e7ok satan kurumlar
            if (!sellers.isEmpty()) {
                y += CardTheme.SECTION_GAP;
                y = drawBrokerageSection(g, "EN \u00c7OK SATAN KURUMLAR",
                        sellers, CardTheme.RED, y);
            }

            // Footer
            y += CardTheme.SECTION_GAP;
            drawFooter(g, data.getBuyerCount(), data.getSellerCount(), y);

        } finally {
            g.dispose();
        }

        return toPngBytes(image);
    }

    /**
     * Ba\u015fl\u0131k b\u00f6l\u00fcm\u00fcn\u00fc \u00e7izer (title + timestamp ayn\u0131 sat\u0131rda, gradient line).
     *
     * @param g         \u00e7izim ba\u011flam\u0131
     * @param timestamp zaman damgas\u0131 metni ("HH:mm")
     * @param y         ba\u015flang\u0131\u00e7 y koordinat\u0131
     * @return b\u00f6l\u00fcm sonras\u0131 y koordinat\u0131
     */
    private int drawHeader(Graphics2D g, String timestamp, int y) {
        int centerX = CardTheme.WIDTH / 2;

        // Ba\u015fl\u0131k: "P\u0130YASA KURUMSAL \u00d6ZET\u0130  |  HH:mm" tek sat\u0131rda, centered
        g.setFont(CardTheme.FONT_TITLE);
        g.setColor(CardTheme.CYAN);
        FontMetrics titleFm = g.getFontMetrics();

        String ts = timestamp != null ? timestamp : "";
        String titleText = "P\u0130YASA KURUMSAL \u00d6ZET\u0130  |  " + ts;
        int titleWidth = titleFm.stringWidth(titleText);
        int titleX = centerX - titleWidth / 2;

        g.drawString(titleText, titleX, y + titleFm.getAscent());

        // Gradient \u00e7izgi (CYAN -> \u015feffaf)
        int lineY = y + HEADER_HEIGHT - 6;
        int leftX = CardTheme.PADDING;
        int rightX = CardTheme.WIDTH - CardTheme.PADDING;
        Paint oldPaint = g.getPaint();
        g.setPaint(new GradientPaint(
                leftX, lineY, CardTheme.CYAN,
                rightX, lineY, new Color(0x00, 0xD4, 0xFF, 0)
        ));
        g.fillRect(leftX, lineY, rightX - leftX, 2);
        g.setPaint(oldPaint);

        return y + HEADER_HEIGHT;
    }

    /**
     * Bir kurum listesi b\u00f6l\u00fcm\u00fcn\u00fc \u00e7izer (ba\u015fl\u0131k + sat\u0131rlar, CARD_BG arka planl\u0131).
     *
     * @param g            \u00e7izim ba\u011flam\u0131
     * @param sectionTitle b\u00f6l\u00fcm ba\u015fl\u0131\u011f\u0131 (\u00f6r. "EN \u00c7OK ALAN KURUMLAR")
     * @param items        kurum \u00f6\u011feleri
     * @param accentColor  ba\u015fl\u0131k ve hacim de\u011feri rengi (ye\u015fil/k\u0131rm\u0131z\u0131)
     * @param y            ba\u015flang\u0131\u00e7 y koordinat\u0131
     * @return b\u00f6l\u00fcm sonras\u0131 y koordinat\u0131
     */
    private int drawBrokerageSection(Graphics2D g, String sectionTitle,
                                     List<AkdSummaryCardData.BrokerageItem> items,
                                     Color accentColor, int y) {
        int leftX = CardTheme.PADDING;
        int sectionWidth = CardTheme.WIDTH - 2 * CardTheme.PADDING;
        int sectionHeight = measureSectionHeight(items.size());

        // Arka plan
        SectionDrawHelper.drawSectionBackground(g, leftX, y, sectionWidth, sectionHeight);

        int innerX = leftX + CardTheme.SECTION_PADDING;
        int innerRight = leftX + sectionWidth - CardTheme.SECTION_PADDING;
        int currentY = y + CardTheme.SECTION_PADDING;

        // B\u00f6l\u00fcm ba\u015fl\u0131\u011f\u0131 (renkli daire ikonu + metin)
        g.setFont(CardTheme.FONT_HEADER);
        FontMetrics headerFm = g.getFontMetrics();
        int circleSize = 14;
        int circleY = currentY + headerFm.getAscent() - circleSize + 2;
        g.setColor(accentColor);
        g.fillOval(innerX, circleY, circleSize, circleSize);
        g.drawString(sectionTitle, innerX + circleSize + 8, currentY + headerFm.getAscent());
        currentY += SECTION_HEADER_HEIGHT;

        // Kurum sat\u0131rlar\u0131
        for (int i = 0; i < items.size(); i++) {
            AkdSummaryCardData.BrokerageItem item = items.get(i);
            currentY = drawBrokerageRow(g, i + 1, item, accentColor, innerX, innerRight, currentY);
        }

        return y + sectionHeight;
    }

    /**
     * Tek bir kurum sat\u0131r\u0131n\u0131 \u00e7izer.
     *
     * <p>Format: "N. KurumAd\u0131    2.01 Milyar \u20ba"</p>
     * <p>2 s\u00fctun: kurum ad\u0131 (sol), hacim (sa\u011f).</p>
     *
     * @param g           \u00e7izim ba\u011flam\u0131
     * @param rank        s\u0131ra numaras\u0131 (1-5)
     * @param item        kurum \u00f6\u011fesi
     * @param accentColor hacim de\u011feri rengi
     * @param leftX       sol s\u0131n\u0131r x koordinat\u0131
     * @param rightX      sa\u011f s\u0131n\u0131r x koordinat\u0131
     * @param y           sat\u0131r ba\u015flang\u0131\u00e7 y koordinat\u0131
     * @return sat\u0131r sonras\u0131 y koordinat\u0131
     */
    private int drawBrokerageRow(Graphics2D g, int rank, AkdSummaryCardData.BrokerageItem item,
                                 Color accentColor, int leftX, int rightX, int y) {
        g.setFont(CardTheme.FONT_BODY);
        FontMetrics bodyFm = g.getFontMetrics();
        int baseline = y + bodyFm.getAscent();

        // 1. S\u0131ra + Kurum ad\u0131 (sol)
        g.setColor(CardTheme.TEXT_PRIMARY);
        String nameText = rank + ". " + safe(item.getName());
        g.drawString(nameText, leftX, baseline);

        // 2. Hacim (sa\u011f, renkli)
        g.setColor(accentColor);
        String volumeText = safe(item.getVolume()) + " \u20ba";
        int volumeWidth = bodyFm.stringWidth(volumeText);
        g.drawString(volumeText, rightX - volumeWidth, baseline);

        return y + AKD_ROW_HEIGHT;
    }

    /**
     * Footer b\u00f6l\u00fcm\u00fcn\u00fc \u00e7izer (ay\u0131r\u0131c\u0131 \u00e7izgi + \u00f6zet + copyright).
     *
     * @param g           \u00e7izim ba\u011flam\u0131
     * @param buyerCount  toplam al\u0131c\u0131 kurum say\u0131s\u0131
     * @param sellerCount toplam sat\u0131c\u0131 kurum say\u0131s\u0131
     * @param y           ba\u015flang\u0131\u00e7 y koordinat\u0131
     */
    private void drawFooter(Graphics2D g, long buyerCount, long sellerCount, int y) {
        int leftX = CardTheme.PADDING;
        int rightX = CardTheme.WIDTH - CardTheme.PADDING;

        // Ay\u0131r\u0131c\u0131 \u00e7izgi
        SectionDrawHelper.drawDividerLine(g, leftX, y, rightX - leftX);

        int textY = y + 8;

        // Sat\u0131r 1: \u00d6zet (sol taraf)
        g.setFont(CardTheme.FONT_SMALL);
        g.setColor(CardTheme.TEXT_SECONDARY);
        FontMetrics fm = g.getFontMetrics();
        String summary = "Piyasa: " + buyerCount + " al\u0131c\u0131 | " + sellerCount + " sat\u0131c\u0131 kurum";
        g.drawString(summary, leftX, textY + fm.getAscent());

        // Sat\u0131r 2: Copyright (sa\u011f taraf)
        int line2Y = textY + fm.getHeight() + 2;
        g.setColor(CardTheme.CYAN);
        String copyright = "\u00a9 ScyBorsa Bot";
        int copyrightWidth = fm.stringWidth(copyright);
        g.drawString(copyright, rightX - copyrightWidth, line2Y + fm.getAscent());
    }

    /**
     * Bir kurum b\u00f6l\u00fcm\u00fcn\u00fcn toplam y\u00fcksekli\u011fini hesaplar.
     *
     * @param itemCount kurum \u00f6\u011fe say\u0131s\u0131
     * @return b\u00f6l\u00fcm y\u00fcksekli\u011fi (piksel)
     */
    private int measureSectionHeight(int itemCount) {
        return 2 * CardTheme.SECTION_PADDING
                + SECTION_HEADER_HEIGHT
                + itemCount * AKD_ROW_HEIGHT;
    }

    /**
     * Graphics2D rendering kalite ayarlar\u0131n\u0131 uygular.
     *
     * @param g \u00e7izim ba\u011flam\u0131
     */
    private void applyRenderingHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    /**
     * Yuvarlak k\u00f6\u015feli kart arka plan\u0131n\u0131 \u00e7izer.
     *
     * <p>\u00d6nce tam dikd\u00f6rtgen ile doldurur (\u015feffaf k\u00f6\u015fe engeli),
     * ard\u0131ndan yuvarlak k\u00f6\u015feli kart \u00e7izer.</p>
     *
     * @param g      \u00e7izim ba\u011flam\u0131
     * @param width  kart geni\u015fli\u011fi
     * @param height kart y\u00fcksekli\u011fi
     */
    private void drawBackground(Graphics2D g, int width, int height) {
        // \u00d6nce tam dikd\u00f6rtgen ile doldur (\u015feffaf k\u00f6\u015fe engeli \u2014 a\u00e7\u0131k tema Telegram'da beyaz g\u00f6r\u00fcnmez)
        g.setColor(CardTheme.BG);
        g.fillRect(0, 0, width, height);
        // Ard\u0131ndan yuvarlak k\u00f6\u015feli kart \u00e7iz (ayn\u0131 renk, g\u00f6rsel etki yok ama pattern korunur)
        g.fillRoundRect(0, 0, width, height,
                CardTheme.CORNER_RADIUS * 2, CardTheme.CORNER_RADIUS * 2);
    }

    /**
     * BufferedImage'i PNG bayt dizisine d\u00f6n\u00fc\u015ft\u00fcr\u00fcr.
     *
     * @param image kaynak g\u00f6r\u00fcnt\u00fc
     * @return PNG format\u0131nda bayt dizisi
     * @throws IllegalStateException d\u00f6n\u00fc\u015ft\u00fcrme ba\u015far\u0131s\u0131z olursa
     */
    private byte[] toPngBytes(BufferedImage image) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "PNG", baos)) {
                throw new IllegalStateException("PNG writer bulunamad\u0131.");
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("PNG d\u00f6n\u00fc\u015ft\u00fcrme hatas\u0131.", e);
        }
    }

    /**
     * Null-safe string d\u00f6n\u00fc\u015f\u00fcm\u00fc.
     *
     * @param value metin de\u011feri
     * @return de\u011fer veya bo\u015f string
     */
    private String safe(String value) {
        return value != null ? value : "";
    }

    /**
     * Null-safe liste d\u00f6n\u00fc\u015f\u00fcm\u00fc.
     *
     * @param list kaynak liste
     * @return liste veya bo\u015f liste
     */
    private List<AkdSummaryCardData.BrokerageItem> safeList(
            List<AkdSummaryCardData.BrokerageItem> list) {
        return list != null ? list : List.of();
    }
}
