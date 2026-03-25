package com.scyborsa.api.service.telegram.infographic;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Telegram hisse infografik kartı oluşturucu servis.
 *
 * <p>Koyu temalı PNG infografik görüntüler üretir. İki geçişli render
 * mimarisi kullanır: önce tüm bölüm yükseklikleri hesaplanır, ardından
 * uygun boyutta bir {@link BufferedImage} oluşturularak bölümler sırayla çizilir.</p>
 *
 * <p>Bölüm sırası: Header → Fon Pozisyonları → Kurum Dağılımı →
 * Takas Dağılımı → Emir Defteri → Footer</p>
 *
 * @see CardSection
 * @see StockCardData
 */
@Slf4j
@Service
public class StockCardRenderer {

    /** Sıralı bölüm listesi. */
    private final List<CardSection> sections = List.of(
            new HeaderSection(),
            new FonPozisyonSection(),
            new KurumDagilimiSection(),
            new TakasDagilimiSection(),
            new EmirDefteriSection(),
            new FooterSection()
    );

    /**
     * NotoSans font dosyalarını yükler.
     * Uygulama başlangıcında Spring tarafından otomatik çağrılır.
     */
    @PostConstruct
    public void init() {
        CardTheme.init();
        log.info("StockCardRenderer başlatıldı — {} bölüm kayıtlı.", sections.size());
    }

    /**
     * Verilen hisse verisi için PNG formatında infografik kart oluşturur.
     *
     * <p>İki geçişli render:
     * <ol>
     *   <li><strong>Ölçüm:</strong> Tüm bölüm yükseklikleri toplanarak
     *       toplam kart yüksekliği hesaplanır.</li>
     *   <li><strong>Çizim:</strong> {@link BufferedImage} oluşturulur,
     *       arka plan ve bölümler sırayla çizilir.</li>
     * </ol></p>
     *
     * @param data hisse kartı verisi
     * @return PNG formatında bayt dizisi
     * @throws IllegalStateException görüntü PNG'ye dönüştürülemezse
     */
    public byte[] renderCard(StockCardData data) {
        // 1. Geçiş: Yükseklik hesaplama
        int totalHeight = 2 * CardTheme.PADDING; // Üst + alt padding
        int nonEmptySectionCount = 0;

        for (CardSection section : sections) {
            int sectionHeight = section.measureHeight(data);
            if (sectionHeight > 0) {
                totalHeight += sectionHeight;
                nonEmptySectionCount++;
            }
        }

        // Bölümler arası boşluklar (n-1 adet)
        if (nonEmptySectionCount > 1) {
            totalHeight += (nonEmptySectionCount - 1) * CardTheme.SECTION_GAP;
        }

        // 2. Geçiş: Çizim
        BufferedImage image = new BufferedImage(
                CardTheme.WIDTH, totalHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();

        try {
            applyRenderingHints(g);
            drawBackground(g, CardTheme.WIDTH, totalHeight);

            int y = CardTheme.PADDING;
            boolean firstDrawn = false;

            for (CardSection section : sections) {
                int sectionHeight = section.measureHeight(data);
                if (sectionHeight <= 0) {
                    continue;
                }

                // Bölümler arası boşluk (ilk bölümden sonra)
                if (firstDrawn) {
                    y += CardTheme.SECTION_GAP;
                }

                section.render(g, data, y, CardTheme.WIDTH);
                y += sectionHeight;
                firstDrawn = true;
            }
        } finally {
            g.dispose();
        }

        return toPngBytes(image);
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
        g.setColor(CardTheme.BG);
        g.fillRoundRect(0, 0, width, height, CardTheme.CORNER_RADIUS * 2, CardTheme.CORNER_RADIUS * 2);
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
}
