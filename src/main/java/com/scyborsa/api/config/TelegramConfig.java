package com.scyborsa.api.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Telegram Bot API yapilandirma sinifi.
 *
 * <p>{@code telegram} prefix'i altindaki property'leri okur:</p>
 * <ul>
 *   <li>{@code telegram.enabled} - Telegram entegrasyonu aktif mi</li>
 *   <li>{@code telegram.bot.*} - Bot token, chat ID, topic ID</li>
 *   <li>{@code telegram.ai.*} - AI yorum topic ayarlari</li>
 *   <li>{@code telegram.screenshot.*} - Chart screenshot ayarlari</li>
 *   <li>{@code telegram.kgs.*} - Kurumsal Guc Skoru filtre ayarlari</li>
 *   <li>{@code telegram.chart-base-url} - TradingView chart base URL'i (Telegram mesajlarindaki chart linki)</li>
 *   <li>{@code telegram.send-rate-limit-ms} - Telegram mesaj gonderimi arasi bekleme suresi (milisaniye)</li>
 * </ul>
 *
 * @see com.scyborsa.api.service.telegram.TelegramClient
 * @see com.scyborsa.api.service.BistGunlukSendTelegramJob
 */
@Slf4j
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "telegram")
public class TelegramConfig {

    /** Telegram entegrasyonu aktif mi? Default: false. */
    private boolean enabled = false;

    /** Bot ayarlari. */
    private Bot bot = new Bot();

    /** AI yorum topic ayarlari. */
    private Ai ai = new Ai();

    /** Chart screenshot ayarlari. */
    private Screenshot screenshot = new Screenshot();

    /** Kurumsal Guc Skoru filtre ayarlari. */
    private Kgs kgs = new Kgs();

    /** KAP haber topic ayarları. */
    private Kap kap = new Kap();

    /** TradingView chart base URL'i (Telegram mesajlarindaki chart linki). */
    private String chartBaseUrl = "https://www.tradingview.com/chart/?symbol=BIST:";

    /** Telegram mesaj gonderimi arasi bekleme suresi (milisaniye). */
    private long sendRateLimitMs = 200;

    /**
     * Startup'ta config dogrulamasi.
     * Telegram aktifken token veya chatId bossa uyari loglar.
     */
    @PostConstruct
    public void validate() {
        if (!enabled) return;
        if (bot.getToken() == null || bot.getToken().isBlank()) {
            log.error("[TELEGRAM-CONFIG] telegram.enabled=true ama bot.token bos! "
                    + "Mesaj gonderimleri basarisiz olacak.");
        }
        if (bot.getChatId() == null || bot.getChatId().isBlank()) {
            log.error("[TELEGRAM-CONFIG] telegram.enabled=true ama bot.chat-id bos! "
                    + "Mesaj gonderimleri basarisiz olacak.");
        }
    }

    /**
     * Telegram Bot ayarlari.
     */
    @Getter
    @Setter
    public static class Bot {
        /** Telegram Bot API token. Ortam degiskeninden alinir. */
        private String token;

        /** Hedef chat/group ID. */
        private String chatId;

        /** Mesaj gonderilecek topic (message_thread_id). 0 = genel topic. */
        private int topicId = 0;
    }

    /**
     * AI yorum topic ayarlari.
     */
    @Getter
    @Setter
    public static class Ai {
        /** AI yorumlarinin gonderilecegi topic ID. */
        private int topicId = 8;

        /** AI yorum ozelligi aktif mi? */
        private boolean enabled = false;

        /** AI topic gonderilemezse ana topic'e fallback yap. */
        private boolean fallbackToMain = true;
    }

    /**
     * Chart screenshot ayarlari.
     */
    @Getter
    @Setter
    public static class Screenshot {
        /** Chart screenshot ozelligi aktif mi? */
        private boolean enabled = false;
    }

    /**
     * Kurumsal Guc Skoru (KGS) filtre ayarlari.
     */
    @Getter
    @Setter
    public static class Kgs {
        /** KGS filtresi aktif mi? true ise esik altindaki hisseler gonderilmez. */
        private boolean filterEnabled = false;

        /** Minimum KGS skoru (0-100). */
        private int minScore = 70;
    }

    /**
     * KAP haber Telegram topic ayarları.
     */
    @Getter
    @Setter
    public static class Kap {
        /** KAP haberlerinin gönderileceği topic ID. 0 = genel topic. */
        private int topicId = 0;

        /** Tek çalışmada gönderilecek maksimum haber sayısı. */
        private int maxPerCycle = 5;
    }
}
