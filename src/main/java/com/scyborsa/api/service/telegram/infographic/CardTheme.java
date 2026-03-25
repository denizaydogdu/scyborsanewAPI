package com.scyborsa.api.service.telegram.infographic;

import lombok.extern.slf4j.Slf4j;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;

/**
 * Telegram infografik kartı için tema sabitleri ve font yönetimi.
 *
 * <p>Renk paleti, boyut sabitleri ve NotoSans font ailesi yüklemesini içerir.
 * {@link #init()} metodu uygulama başlangıcında bir kez çağrılmalıdır.</p>
 */
@Slf4j
public final class CardTheme {

    private CardTheme() {
        // Utility class — instantiation engellendi
    }

    // ===================== Renkler =====================

    /** Ana arka plan rengi. */
    public static final Color BG = new Color(0x1A, 0x1F, 0x2E);

    /** Kart bölüm arka plan rengi. */
    public static final Color CARD_BG = new Color(0x23, 0x2A, 0x3E);

    /** Birincil metin rengi. */
    public static final Color TEXT_PRIMARY = new Color(0xE0, 0xE0, 0xE0);

    /** İkincil metin rengi (açıklama, footer vb.). */
    public static final Color TEXT_SECONDARY = new Color(0x90, 0x95, 0xA5);

    /** Vurgu rengi (başlıklar, önemli değerler). */
    public static final Color CYAN = new Color(0x00, 0xD4, 0xFF);

    /** Pozitif değişim / alış rengi. */
    public static final Color GREEN = new Color(0x00, 0xC8, 0x53);

    /** Negatif değişim / satış rengi. */
    public static final Color RED = new Color(0xFF, 0x45, 0x60);

    /** Bölüm ayırıcı çizgi rengi. */
    public static final Color DIVIDER = new Color(0x2E, 0x35, 0x4A);

    /** Bölüm başlık arka plan rengi. */
    public static final Color SECTION_TITLE_BG = new Color(0x19, 0x2E, 0x3F);

    // ===================== Boyut sabitleri =====================

    /** Kart genişliği (piksel). */
    public static final int WIDTH = 800;

    /** Kenar boşluğu (piksel). */
    public static final int PADDING = 28;

    /** Bölümler arası dikey boşluk (piksel). */
    public static final int SECTION_GAP = 16;

    /** Standart satır yüksekliği (piksel). */
    public static final int ROW_HEIGHT = 28;

    /** Kart bölüm köşe yarıçapı (piksel). */
    public static final int CORNER_RADIUS = 8;

    /** Bölüm iç padding (piksel). */
    public static final int SECTION_PADDING = 12;

    // ===================== Fontlar =====================

    /** Başlık fontu (Bold 32pt). */
    public static Font FONT_TITLE;

    /** Bölüm başlık fontu (Bold 18pt). */
    public static Font FONT_HEADER;

    /** Gövde metin fontu (Regular 16pt). */
    public static Font FONT_BODY;

    /** Küçük metin fontu (Regular 14pt). */
    public static Font FONT_SMALL;

    /** Fiyat fontu (Bold 28pt). */
    public static Font FONT_PRICE;

    /** Font yükleme durumu. */
    private static volatile boolean initialized = false;

    /**
     * NotoSans font dosyalarını classpath'ten yükler ve türetilmiş fontları oluşturur.
     *
     * <p>Bu metod idempotent'tir; birden fazla çağrı güvenlidir.</p>
     *
     * @throws IllegalStateException font dosyaları yüklenemezse
     */
    public static synchronized void init() {
        if (initialized) {
            return;
        }

        Font base = loadFont("/fonts/NotoSans-Variable.ttf");

        FONT_TITLE = base.deriveFont(Font.BOLD, 32f);
        FONT_HEADER = base.deriveFont(Font.BOLD, 18f);
        FONT_BODY = base.deriveFont(Font.PLAIN, 16f);
        FONT_SMALL = base.deriveFont(Font.PLAIN, 14f);
        FONT_PRICE = base.deriveFont(Font.BOLD, 28f);

        initialized = true;
        log.info("CardTheme fontları başarıyla yüklendi.");
    }

    /**
     * Classpath'ten bir font dosyası yükler.
     *
     * @param resourcePath font dosyası yolu (ör. "/fonts/NotoSans-Bold.ttf")
     * @return yüklenen {@link Font} nesnesi
     * @throws IllegalStateException font dosyası bulunamazsa veya okunamazsa
     */
    private static Font loadFont(String resourcePath) {
        try (InputStream is = CardTheme.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException("Font dosyası bulunamadı: " + resourcePath);
            }
            Font font = Font.createFont(Font.TRUETYPE_FONT, is);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
            return font;
        } catch (FontFormatException | IOException e) {
            throw new IllegalStateException("Font yüklenemedi: " + resourcePath, e);
        }
    }
}
