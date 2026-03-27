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
 * Piyasa özeti infografik kartı oluşturucu.
 *
 * <p>BIST piyasa özetini (en çok yükselen, düşen ve hacimli hisseler)
 * koyu temalı PNG görüntü olarak render eder. İki geçişli render mimarisi
 * kullanır: önce toplam yükseklik hesaplanır, ardından uygun boyutta
 * {@link BufferedImage} oluşturularak bölümler çizilir.</p>
 *
 * <p>Kart düzeni:</p>
 * <ol>
 *   <li>Başlık: "BIST PİYASA ÖZETİ | HH:mm" + gradient çizgi</li>
 *   <li>Yükselen hisseler bölümü (yeşil başlık + satırlar)</li>
 *   <li>Düşen hisseler bölümü (kırmızı başlık + satırlar)</li>
 *   <li>Hacimli hisseler bölümü (cyan başlık + satırlar, dinamik renk)</li>
 *   <li>Footer: ayırıcı çizgi + "© ScyBorsa Bot"</li>
 * </ol>
 *
 * @see MarketSummaryCardData
 * @see CardTheme
 */
@Slf4j
@Component
public class MarketSummaryCardRenderer {

    /** Piyasa satır yüksekliği (piksel) — CardTheme.ROW_HEIGHT (28) ile karıştırılmaması için farklı isim. */
    private static final int MARKET_ROW_HEIGHT = 36;

    /** Bölüm başlık yüksekliği (piksel). */
    private static final int SECTION_HEADER_HEIGHT = 40;

    /** Başlık bölümü yüksekliği (title + timestamp + gradient line). */
    private static final int HEADER_HEIGHT = 80;

    /** Footer bölümü yüksekliği (divider + copyright). */
    private static final int FOOTER_HEIGHT = 44;

    /**
     * NotoSans font dosyalarını yükler.
     * Uygulama başlangıcında Spring tarafından otomatik çağrılır.
     */
    @PostConstruct
    public void init() {
        CardTheme.init();
        log.info("MarketSummaryCardRenderer baslatildi.");
    }

    /**
     * Verilen piyasa verisi için PNG formatında infografik kart oluşturur.
     *
     * <p>İki geçişli render:
     * <ol>
     *   <li><strong>Ölçüm:</strong> Bölüm yükseklikleri toplanarak toplam kart yüksekliği hesaplanır.</li>
     *   <li><strong>Çizim:</strong> {@link BufferedImage} oluşturulur, bölümler sırayla çizilir.</li>
     * </ol></p>
     *
     * @param data piyasa özet kartı verisi
     * @return PNG formatında bayt dizisi, render başarısız olursa {@code null}
     */
    public byte[] renderCard(MarketSummaryCardData data) {
        try {
            return doRender(data);
        } catch (Exception e) {
            log.error("[MARKET-CARD] Render hatasi: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Asıl render işlemini gerçekleştirir.
     *
     * @param data piyasa özet kartı verisi
     * @return PNG formatında bayt dizisi
     */
    private byte[] doRender(MarketSummaryCardData data) {
        List<MarketSummaryCardData.StockItem> rising = safeList(data.getRisingStocks());
        List<MarketSummaryCardData.StockItem> falling = safeList(data.getFallingStocks());
        List<MarketSummaryCardData.StockItem> volume = safeList(data.getVolumeStocks());

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

        if (!volume.isEmpty()) {
            totalHeight += CardTheme.SECTION_GAP;
            totalHeight += measureSectionHeight(volume.size());
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

            // Yükselen hisseler
            if (!rising.isEmpty()) {
                y += CardTheme.SECTION_GAP;
                y = drawStockSection(g, "EN \u00C7OK Y\u00DCKSELEN",
                        rising, CardTheme.GREEN, false, y);
            }

            // Düşen hisseler
            if (!falling.isEmpty()) {
                y += CardTheme.SECTION_GAP;
                y = drawStockSection(g, "EN \u00C7OK D\u00DC\u015EEN",
                        falling, CardTheme.RED, false, y);
            }

            // Hacimli hisseler
            if (!volume.isEmpty()) {
                y += CardTheme.SECTION_GAP;
                y = drawStockSection(g, "EN Y\u00DCKSEK HAC\u0130ML\u0130",
                        volume, CardTheme.CYAN, true, y);
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
     * Başlık bölümünü çizer (title + timestamp aynı satırda, gradient line).
     *
     * @param g         çizim bağlamı
     * @param timestamp zaman damgası metni ("HH:mm")
     * @param y         başlangıç y koordinatı
     * @return bölüm sonrası y koordinatı
     */
    private int drawHeader(Graphics2D g, String timestamp, int y) {
        int centerX = CardTheme.WIDTH / 2;

        // Başlık: "BIST PİYASA ÖZETİ  |  HH:mm" tek satırda, centered
        g.setFont(CardTheme.FONT_TITLE);
        g.setColor(CardTheme.CYAN);
        FontMetrics titleFm = g.getFontMetrics();

        String ts = timestamp != null ? timestamp : "";
        String titleText = "BIST P\u0130YASA \u00D6ZET\u0130  |  " + ts;
        int titleWidth = titleFm.stringWidth(titleText);
        int titleX = centerX - titleWidth / 2;

        g.drawString(titleText, titleX, y + titleFm.getAscent());

        // Gradient çizgi (CYAN -> seffaf)
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
     * Bir hisse listesi bölümünü çizer (başlık + satırlar, CARD_BG arka planlı).
     *
     * @param g              çizim bağlamı
     * @param sectionTitle   bölüm başlığı (ör. "EN COK YUKSELEN")
     * @param items          hisse öğeleri
     * @param headerColor    bölüm başlık rengi ve daire rengi
     * @param dynamicChange  true ise satır değişim rengi dinamik (hacim bölümü), false ise sabit headerColor
     * @param y              başlangıç y koordinatı
     * @return bölüm sonrası y koordinatı
     */
    private int drawStockSection(Graphics2D g, String sectionTitle,
                                 List<MarketSummaryCardData.StockItem> items,
                                 Color headerColor, boolean dynamicChange, int y) {
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
        g.setColor(headerColor);
        g.fillOval(innerX, circleY, circleSize, circleSize);
        g.drawString(sectionTitle, innerX + circleSize + 8, currentY + headerFm.getAscent());
        currentY += SECTION_HEADER_HEIGHT;

        // Hisse satırları
        for (int i = 0; i < items.size(); i++) {
            MarketSummaryCardData.StockItem item = items.get(i);
            Color changeColor = dynamicChange
                    ? (item.getChangePercent() >= 0 ? CardTheme.GREEN : CardTheme.RED)
                    : headerColor;
            currentY = drawStockRow(g, i + 1, item, changeColor, innerX, innerRight, currentY);
        }

        return y + sectionHeight;
    }

    /**
     * Tek bir hisse satırını çizer.
     *
     * <p>Format: "N. TICKER    +X.XX%    X.XX TL    90.6 Bin"</p>
     * <p>4 sütun: ticker (sol), değişim (~%40 sağa hizalı),
     * fiyat (~%65 sağa hizalı), hacim (en sağ, küçük font).</p>
     *
     * @param g           çizim bağlamı
     * @param rank        sıra numarası (1-5)
     * @param item        hisse öğesi
     * @param changeColor değişim rengi
     * @param leftX       sol sınır x koordinatı
     * @param rightX      sağ sınır x koordinatı
     * @param y           satır başlangıç y koordinatı
     * @return satır sonrası y koordinatı
     */
    private int drawStockRow(Graphics2D g, int rank, MarketSummaryCardData.StockItem item,
                             Color changeColor, int leftX, int rightX, int y) {
        int rowWidth = rightX - leftX;
        int changeAlignX = leftX + (int) (rowWidth * 0.40);
        int priceAlignX = leftX + (int) (rowWidth * 0.65);

        // Body font baseline
        g.setFont(CardTheme.FONT_BODY);
        FontMetrics bodyFm = g.getFontMetrics();
        int baseline = y + bodyFm.getAscent();

        // 1. Sıra + Ticker (sol)
        g.setColor(CardTheme.TEXT_PRIMARY);
        g.setFont(CardTheme.FONT_BODY);
        String nameText = rank + ". " + safe(item.getTicker());
        g.drawString(nameText, leftX, baseline);

        // 2. Değişim yüzdesi (~%40 sağa hizalı)
        g.setColor(changeColor);
        g.setFont(CardTheme.FONT_BODY);
        String changeText = String.format("%+.2f%%", item.getChangePercent());
        int changeWidth = bodyFm.stringWidth(changeText);
        g.drawString(changeText, changeAlignX - changeWidth, baseline);

        // 3. Fiyat (~%65 sağa hizalı)
        g.setColor(CardTheme.TEXT_PRIMARY);
        g.setFont(CardTheme.FONT_BODY);
        String priceText = String.format("%.2f\u20BA", item.getPrice());
        int priceWidth = bodyFm.stringWidth(priceText);
        g.drawString(priceText, priceAlignX - priceWidth, baseline);

        // 4. Hacim (en sağ, küçük font)
        g.setColor(CardTheme.TEXT_SECONDARY);
        g.setFont(CardTheme.FONT_SMALL);
        FontMetrics smallFm = g.getFontMetrics();
        String volumeText = safe(item.getVolume());
        int volumeWidth = smallFm.stringWidth(volumeText);
        g.drawString(volumeText, rightX - volumeWidth, baseline);

        return y + MARKET_ROW_HEIGHT;
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
     * Bir hisse bölümünün toplam yüksekliğini hesaplar.
     *
     * @param itemCount hisse öğe sayısı
     * @return bölüm yüksekliği (piksel)
     */
    private int measureSectionHeight(int itemCount) {
        return 2 * CardTheme.SECTION_PADDING
                + SECTION_HEADER_HEIGHT
                + itemCount * MARKET_ROW_HEIGHT;
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
     * <p>Önce tam dikdörtgen ile doldurur (şeffaf köşe engeli),
     * ardından yuvarlak köşeli kart çizer.</p>
     *
     * @param g      çizim bağlamı
     * @param width  kart genişliği
     * @param height kart yüksekliği
     */
    private void drawBackground(Graphics2D g, int width, int height) {
        // Önce tam dikdörtgen ile doldur (seffaf köse engeli — acik tema Telegram'da beyaz görünmez)
        g.setColor(CardTheme.BG);
        g.fillRect(0, 0, width, height);
        // Ardından yuvarlak köseli kart ciz (aynı renk, görsel etki yok ama pattern korunur)
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
                throw new IllegalStateException("PNG writer bulunamadi.");
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("PNG donusturme hatasi.", e);
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
    private List<MarketSummaryCardData.StockItem> safeList(
            List<MarketSummaryCardData.StockItem> list) {
        return list != null ? list : List.of();
    }
}
