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
 * Sektör özeti infografik kartı oluşturucu.
 *
 * <p>BIST sektör özetini koyu temalı PNG görüntü olarak render eder.
 * İki geçişli render mimarisi kullanır: önce toplam yükseklik hesaplanır,
 * ardından uygun boyutta {@link BufferedImage} oluşturularak bölümler çizilir.</p>
 *
 * <p>Kart düzeni:</p>
 * <ol>
 *   <li>Başlık: "BIST SEKTÖR ÖZETİ" + bar chart ikonu (Java2D) + zaman damgası + gradient çizgi</li>
 *   <li>Yükselen sektörler bölümü (yeşil başlık + satırlar)</li>
 *   <li>Düşen sektörler bölümü (kırmızı başlık + satırlar)</li>
 *   <li>Footer: ayırıcı çizgi + "© ScyBorsa Bot"</li>
 * </ol>
 *
 * @see SectorSummaryCardData
 * @see CardTheme
 */
@Slf4j
@Component
public class SectorSummaryCardRenderer {

    /** Sektör satır yüksekliği (piksel) — CardTheme.ROW_HEIGHT (28) ile karıştırılmaması için farklı isim. */
    private static final int SECTOR_ROW_HEIGHT = 36;

    /** Bölüm başlık yüksekliği (piksel). */
    private static final int SECTION_HEADER_HEIGHT = 40;

    /** Başlık bölümü yüksekliği (title + timestamp + gradient line). */
    private static final int HEADER_HEIGHT = 100;

    /** Footer bölümü yüksekliği (divider + copyright). */
    private static final int FOOTER_HEIGHT = 44;

    /**
     * NotoSans font dosyalarını yükler.
     * Uygulama başlangıcında Spring tarafından otomatik çağrılır.
     */
    @PostConstruct
    public void init() {
        CardTheme.init();
        log.info("SectorSummaryCardRenderer başlatıldı.");
    }

    /**
     * Verilen sektör verisi için PNG formatında infografik kart oluşturur.
     *
     * <p>İki geçişli render:
     * <ol>
     *   <li><strong>Ölçüm:</strong> Bölüm yükseklikleri toplanarak toplam kart yüksekliği hesaplanır.</li>
     *   <li><strong>Çizim:</strong> {@link BufferedImage} oluşturulur, bölümler sırayla çizilir.</li>
     * </ol></p>
     *
     * @param data sektör özet kartı verisi
     * @return PNG formatında bayt dizisi, render başarısız olursa {@code null}
     */
    public byte[] renderCard(SectorSummaryCardData data) {
        try {
            return doRender(data);
        } catch (Exception e) {
            log.error("[SECTOR-CARD] Render hatası: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Asıl render işlemini gerçekleştirir.
     *
     * @param data sektör özet kartı verisi
     * @return PNG formatında bayt dizisi
     */
    private byte[] doRender(SectorSummaryCardData data) {
        List<SectorSummaryCardData.SectorItem> rising = safeList(data.getRisingSectors());
        List<SectorSummaryCardData.SectorItem> falling = safeList(data.getFallingSectors());

        // 1. Geçiş: Yükseklik hesaplama
        int totalHeight = 2 * CardTheme.PADDING; // Üst + alt padding
        totalHeight += HEADER_HEIGHT;

        if (!rising.isEmpty()) {
            totalHeight += CardTheme.SECTION_GAP;
            totalHeight += measureSectionHeight(rising.size());
        }

        if (!falling.isEmpty()) {
            totalHeight += CardTheme.SECTION_GAP;
            totalHeight += measureSectionHeight(falling.size());
        }

        totalHeight += CardTheme.SECTION_GAP;
        totalHeight += FOOTER_HEIGHT;

        // 2. Geçiş: Çizim
        BufferedImage image = new BufferedImage(
                CardTheme.WIDTH, totalHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();

        try {
            applyRenderingHints(g);
            drawBackground(g, CardTheme.WIDTH, totalHeight);

            int y = CardTheme.PADDING;

            // Başlık
            y = drawHeader(g, data.getTimestamp(), y);

            // Yükselen sektörler
            if (!rising.isEmpty()) {
                y += CardTheme.SECTION_GAP;
                y = drawSectorSection(g, "EN ÇOK YÜKSELEN SEKTÖRLER",
                        rising, CardTheme.GREEN, y);
            }

            // Düşen sektörler
            if (!falling.isEmpty()) {
                y += CardTheme.SECTION_GAP;
                y = drawSectorSection(g, "EN ÇOK DÜŞEN SEKTÖRLER",
                        falling, CardTheme.RED, y);
            }

            // Footer
            y += CardTheme.SECTION_GAP;
            drawFooter(g, y);

        } finally {
            g.dispose();
        }

        return toPngBytes(image);
    }

    /**
     * Başlık bölümünü çizer (title + timestamp + gradient line).
     *
     * @param g         çizim bağlamı
     * @param timestamp zaman damgası metni
     * @param y         başlangıç y koordinatı
     * @return bölüm sonrası y koordinatı
     */
    private int drawHeader(Graphics2D g, String timestamp, int y) {
        int centerX = CardTheme.WIDTH / 2;

        // Başlık: BIST SEKTÖR ÖZETİ (emoji yerine Java2D ikon)
        g.setFont(CardTheme.FONT_TITLE);
        g.setColor(CardTheme.CYAN);
        String title = "BIST SEKTÖR ÖZETİ";
        FontMetrics titleFm = g.getFontMetrics();
        int titleWidth = titleFm.stringWidth(title);
        int titleX = centerX - titleWidth / 2;

        // Başlık soluna küçük bar chart ikonu (Java2D ile çizilmiş)
        int iconSize = 20;
        int iconX = titleX - iconSize - 10;
        int iconY = y + titleFm.getAscent() - iconSize + 2;
        g.fillRect(iconX, iconY + 10, 5, 10);           // kısa çubuk
        g.fillRect(iconX + 7, iconY + 4, 5, 16);        // orta çubuk
        g.fillRect(iconX + 14, iconY, 5, 20);            // uzun çubuk

        g.drawString(title, titleX, y + titleFm.getAscent());

        // Zaman damgası
        int tsY = y + titleFm.getAscent() + 28;
        g.setFont(CardTheme.FONT_SMALL);
        g.setColor(CardTheme.TEXT_SECONDARY);
        FontMetrics smallFm = g.getFontMetrics();
        String ts = timestamp != null ? timestamp : "";
        int tsWidth = smallFm.stringWidth(ts);
        g.drawString(ts, centerX - tsWidth / 2, tsY);

        // Gradient çizgi (CYAN → şeffaf)
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
     * Bir sektör listesi bölümünü çizer (başlık + satırlar, CARD_BG arka planlı).
     *
     * @param g           çizim bağlamı
     * @param sectionTitle bölüm başlığı (ör. "EN ÇOK YÜKSELEN SEKTÖRLER")
     * @param items       sektör öğeleri
     * @param changeColor değişim yüzdesi rengi (yeşil/kırmızı)
     * @param y           başlangıç y koordinatı
     * @return bölüm sonrası y koordinatı
     */
    private int drawSectorSection(Graphics2D g, String sectionTitle,
                                  List<SectorSummaryCardData.SectorItem> items,
                                  Color changeColor, int y) {
        int leftX = CardTheme.PADDING;
        int sectionWidth = CardTheme.WIDTH - 2 * CardTheme.PADDING;
        int sectionHeight = measureSectionHeight(items.size());

        // Arka plan
        SectionDrawHelper.drawSectionBackground(g, leftX, y, sectionWidth, sectionHeight);

        int innerX = leftX + CardTheme.SECTION_PADDING;
        int innerRight = leftX + sectionWidth - CardTheme.SECTION_PADDING;
        int currentY = y + CardTheme.SECTION_PADDING;

        // Bölüm başlığı (renkli daire ikonu + metin)
        g.setFont(CardTheme.FONT_HEADER);
        FontMetrics headerFm = g.getFontMetrics();
        int circleSize = 14;
        int circleY = currentY + headerFm.getAscent() - circleSize + 2;
        g.setColor(changeColor);
        g.fillOval(innerX, circleY, circleSize, circleSize);
        g.drawString(sectionTitle, innerX + circleSize + 8, currentY + headerFm.getAscent());
        currentY += SECTION_HEADER_HEIGHT;

        // Sektör satırları
        for (int i = 0; i < items.size(); i++) {
            SectorSummaryCardData.SectorItem item = items.get(i);
            currentY = drawSectorRow(g, i + 1, item, changeColor, innerX, innerRight, currentY);
        }

        return y + sectionHeight;
    }

    /**
     * Tek bir sektör satırını çizer.
     *
     * <p>Format: "N. SektorAdı    +X.XX%    (N hisse)"</p>
     *
     * @param g           çizim bağlamı
     * @param rank        sıra numarası (1-5)
     * @param item        sektör öğesi
     * @param changeColor değişim rengi
     * @param leftX       sol sınır x koordinatı
     * @param rightX      sağ sınır x koordinatı
     * @param y           satır başlangıç y koordinatı
     * @return satır sonrası y koordinatı
     */
    private int drawSectorRow(Graphics2D g, int rank, SectorSummaryCardData.SectorItem item,
                              Color changeColor, int leftX, int rightX, int y) {
        FontMetrics bodyFm;
        FontMetrics smallFm;

        // Hisse sayısı (en sağda, küçük font)
        g.setFont(CardTheme.FONT_SMALL);
        smallFm = g.getFontMetrics();
        String stockCountText = "(" + item.getStockCount() + " hisse)";
        int stockCountWidth = smallFm.stringWidth(stockCountText);
        int stockCountX = rightX - stockCountWidth;

        // Değişim yüzdesi (hisse sayısının solunda)
        g.setFont(CardTheme.FONT_BODY);
        bodyFm = g.getFontMetrics();
        String changeText = String.format("%+.2f%%", item.getChangePercent());
        int changeWidth = bodyFm.stringWidth(changeText);
        int changeX = stockCountX - changeWidth - 16;

        int baseline = y + bodyFm.getAscent();

        // Sıra + sektör adı (sol taraf)
        g.setColor(CardTheme.TEXT_PRIMARY);
        g.setFont(CardTheme.FONT_BODY);
        String nameText = rank + ". " + safe(item.getName());
        g.drawString(nameText, leftX, baseline);

        // Değişim yüzdesi
        g.setColor(changeColor);
        g.setFont(CardTheme.FONT_BODY);
        g.drawString(changeText, changeX, baseline);

        // Hisse sayısı (body text ile aynı baseline)
        g.setColor(CardTheme.TEXT_SECONDARY);
        g.setFont(CardTheme.FONT_SMALL);
        g.drawString(stockCountText, stockCountX, baseline);

        return y + SECTOR_ROW_HEIGHT;
    }

    /**
     * Footer bölümünü çizer (ayırıcı çizgi + copyright).
     *
     * @param g çizim bağlamı
     * @param y başlangıç y koordinatı
     */
    private void drawFooter(Graphics2D g, int y) {
        int leftX = CardTheme.PADDING;
        int rightX = CardTheme.WIDTH - CardTheme.PADDING;

        // Ayırıcı çizgi
        SectionDrawHelper.drawDividerLine(g, leftX, y, rightX - leftX);

        // Copyright
        g.setFont(CardTheme.FONT_SMALL);
        g.setColor(CardTheme.CYAN);
        String copyright = "© ScyBorsa Bot";
        FontMetrics fm = g.getFontMetrics();
        int copyrightWidth = fm.stringWidth(copyright);
        g.drawString(copyright, rightX - copyrightWidth, y + 8 + fm.getAscent());
    }

    /**
     * Bir sektör bölümünün toplam yüksekliğini hesaplar.
     *
     * @param itemCount sektör öğe sayısı
     * @return bölüm yüksekliği (piksel)
     */
    private int measureSectionHeight(int itemCount) {
        return 2 * CardTheme.SECTION_PADDING
                + SECTION_HEADER_HEIGHT
                + itemCount * SECTOR_ROW_HEIGHT;
    }

    /**
     * Graphics2D rendering kalite ayarlarını uygular.
     *
     * @param g çizim bağlamı
     */
    private void applyRenderingHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    /**
     * Yuvarlak köşeli kart arka planını çizer.
     *
     * @param g      çizim bağlamı
     * @param width  kart genişliği
     * @param height kart yüksekliği
     */
    private void drawBackground(Graphics2D g, int width, int height) {
        // Önce tam dikdörtgen ile doldur (şeffaf köşe engeli — açık tema Telegram'da beyaz görünmez)
        g.setColor(CardTheme.BG);
        g.fillRect(0, 0, width, height);
        // Ardından yuvarlak köşeli kart çiz (aynı renk, görsel etki yok ama pattern korunur)
        g.fillRoundRect(0, 0, width, height,
                CardTheme.CORNER_RADIUS * 2, CardTheme.CORNER_RADIUS * 2);
    }

    /**
     * BufferedImage'i PNG bayt dizisine dönüştürür.
     *
     * @param image kaynak görüntü
     * @return PNG formatında bayt dizisi
     * @throws IllegalStateException dönüştürme başarısız olursa
     */
    private byte[] toPngBytes(BufferedImage image) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "PNG", baos)) {
                throw new IllegalStateException("PNG writer bulunamadı.");
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("PNG dönüştürme hatası.", e);
        }
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

    /**
     * Null-safe liste dönüşümü.
     *
     * @param list kaynak liste
     * @return liste veya boş liste
     */
    private List<SectorSummaryCardData.SectorItem> safeList(
            List<SectorSummaryCardData.SectorItem> list) {
        return list != null ? list : List.of();
    }
}
