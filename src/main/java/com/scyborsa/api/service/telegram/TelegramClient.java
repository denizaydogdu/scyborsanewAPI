package com.scyborsa.api.service.telegram;

import com.scyborsa.api.config.TelegramConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Telegram Bot API HTTP istemcisi.
 *
 * <p>Java 11 {@link HttpClient} kullanarak Telegram Bot API'ye
 * sendMessage ve sendPhoto istekleri gonderir.</p>
 *
 * <p>Konfiguerasyon {@link TelegramConfig} uezerinden saglanir
 * (bot token, chat ID, topic ID).</p>
 *
 * @see TelegramConfig
 * @see TelegramSendService
 */
@Slf4j
@Service
public class TelegramClient {

    /** Telegram Bot API temel URL'i. */
    private static final String TELEGRAM_API_BASE = "https://api.telegram.org/bot";

    /** sendMessage icin maksimum metin uzunlugu (karakter). */
    private static final int MAX_TEXT_LENGTH = 4096;

    /** sendPhoto caption icin maksimum uzunluk (karakter). */
    private static final int MAX_CAPTION_LENGTH = 1024;

    /** HTTP baglanti zaman asimi suresi. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** HTTP istek zaman asimi suresi. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /** Telegram bot yapilandirma bilgileri. */
    private final TelegramConfig config;

    /** Java 11 HTTP istemcisi. */
    private final HttpClient httpClient;

    /**
     * Constructor injection ile TelegramConfig'i alir ve HTTP client olusturur.
     *
     * @param config Telegram bot yapilandirmasi
     */
    public TelegramClient(TelegramConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    // ==================== PUBLIC API ====================

    /**
     * Default chat ve topic'e HTML mesaj gonderir.
     *
     * @param html HTML formatinda mesaj
     * @return {@code true} ise basarili gonderim
     */
    public boolean sendHtmlMessage(String html) {
        return sendHtmlMessage(html, config.getBot().getChatId(), config.getBot().getTopicId());
    }

    /**
     * Belirtilen chat ve topic'e HTML mesaj gonderir.
     *
     * @param html HTML formatinda mesaj
     * @param chatId hedef chat ID
     * @param topicId mesaj thread ID (0 = genel)
     * @return {@code true} ise basarili gonderim
     */
    public boolean sendHtmlMessage(String html, String chatId, int topicId) {
        if (html == null || html.isBlank()) {
            log.warn("[TELEGRAM-CLIENT] Mesaj bos, gonderim atlaniyor");
            return false;
        }

        String truncated = truncateUtf8(html, MAX_TEXT_LENGTH);
        return doSendMessage(chatId, truncated, topicId, "HTML");
    }

    /**
     * Belirli bir topic'e HTML mesaj gonderir (default chat ID kullanir).
     *
     * @param html HTML formatinda mesaj
     * @param topicId hedef topic ID
     * @return {@code true} ise basarili gonderim
     */
    public boolean sendHtmlMessageToTopic(String html, int topicId) {
        return sendHtmlMessage(html, config.getBot().getChatId(), topicId);
    }

    /**
     * Default chat ve topic'e foto + caption gonderir.
     *
     * @param imageBytes PNG binary veri (Content-Type: image/png)
     * @param caption HTML formatinda aciklama
     */
    public void sendPhoto(byte[] imageBytes, String caption) {
        sendPhoto(imageBytes, caption, config.getBot().getChatId(), config.getBot().getTopicId());
    }

    /**
     * Belirtilen chat ve topic'e foto + caption gonderir.
     *
     * <p>Telegram sendPhoto API: multipart/form-data ile binary image gonderir.</p>
     *
     * @param imageBytes PNG binary veri (Content-Type: image/png)
     * @param caption HTML formatinda aciklama (max 1024 byte)
     * @param chatId hedef chat ID
     * @param topicId mesaj thread ID (0 = genel)
     */
    public void sendPhoto(byte[] imageBytes, String caption, String chatId, int topicId) {
        if (imageBytes == null || imageBytes.length == 0) {
            log.warn("[TELEGRAM-CLIENT] Image bytes bos, foto gonderimi atlaniyor");
            return;
        }

        doSendPhoto(chatId, imageBytes, caption, topicId);
    }

    // ==================== INTERNAL ====================

    /**
     * Telegram sendMessage API cagrisi.
     *
     * @param chatId hedef chat
     * @param text mesaj metni
     * @param topicId thread ID (0 ise kullanilmaz)
     * @param parseMode parse modu (HTML/Markdown)
     * @return {@code true} ise HTTP 200 basarili
     */
    private boolean doSendMessage(String chatId, String text, int topicId, String parseMode) {
        try {
            String url = TELEGRAM_API_BASE + config.getBot().getToken() + "/sendMessage";

            StringBuilder json = new StringBuilder();
            json.append("{\"chat_id\":\"").append(escapeJson(chatId)).append("\"");

            if (topicId > 0) {
                json.append(",\"message_thread_id\":").append(topicId);
            }

            json.append(",\"text\":\"").append(escapeJson(text)).append("\"");

            if (parseMode != null) {
                json.append(",\"parse_mode\":\"").append(parseMode).append("\"");
            }

            json.append(",\"disable_web_page_preview\":true}");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                log.info("[TELEGRAM-CLIENT] Mesaj gonderildi | chatId={} | topicId={} | {} karakter",
                        chatId, topicId, text.length());
                return true;
            } else {
                log.error("[TELEGRAM-CLIENT] Mesaj gonderilemedi | status={} | response={}",
                        response.statusCode(), maskTokenInMessage(response.body()));
                return false;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[TELEGRAM-CLIENT] sendMessage interrupted");
            return false;
        } catch (Exception e) {
            log.error("[TELEGRAM-CLIENT] sendMessage hatasi: {}", maskTokenInMessage(e.getMessage()));
            return false;
        }
    }

    /**
     * Telegram sendPhoto API cagrisi (multipart/form-data).
     *
     * @param chatId hedef chat
     * @param image binary image verisi
     * @param caption HTML caption
     * @param topicId thread ID (0 ise kullanilmaz)
     */
    private void doSendPhoto(String chatId, byte[] image, String caption, int topicId) {
        try {
            String url = TELEGRAM_API_BASE + config.getBot().getToken() + "/sendPhoto";
            String boundary = "----ScyBorsaBoundary" + System.nanoTime() + java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000);

            byte[] body = buildMultipartBody(boundary, chatId, image, caption, topicId);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                log.info("[TELEGRAM-CLIENT] Foto gonderildi | chatId={} | {}KB",
                        chatId, image.length / 1024);
            } else {
                log.error("[TELEGRAM-CLIENT] Foto gonderilemedi | status={} | response={}",
                        response.statusCode(), maskTokenInMessage(response.body()));
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[TELEGRAM-CLIENT] sendPhoto interrupted");
        } catch (Exception e) {
            log.error("[TELEGRAM-CLIENT] sendPhoto hatasi: {}", maskTokenInMessage(e.getMessage()));
        }
    }

    /**
     * Multipart/form-data body olusturur (sendPhoto icin).
     */
    private byte[] buildMultipartBody(String boundary, String chatId,
                                       byte[] image, String caption, int topicId) {
        try {
            var baos = new java.io.ByteArrayOutputStream();
            String crlf = "\r\n";
            String dd = "--";

            // chat_id
            baos.write((dd + boundary + crlf).getBytes(StandardCharsets.UTF_8));
            baos.write(("Content-Disposition: form-data; name=\"chat_id\"" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
            baos.write((chatId + crlf).getBytes(StandardCharsets.UTF_8));

            // message_thread_id (optional)
            if (topicId > 0) {
                baos.write((dd + boundary + crlf).getBytes(StandardCharsets.UTF_8));
                baos.write(("Content-Disposition: form-data; name=\"message_thread_id\"" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
                baos.write((String.valueOf(topicId) + crlf).getBytes(StandardCharsets.UTF_8));
            }

            // photo (binary)
            baos.write((dd + boundary + crlf).getBytes(StandardCharsets.UTF_8));
            baos.write(("Content-Disposition: form-data; name=\"photo\"; filename=\"chart.png\"" + crlf).getBytes(StandardCharsets.UTF_8));
            baos.write(("Content-Type: image/png" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
            baos.write(image);
            baos.write(crlf.getBytes(StandardCharsets.UTF_8));

            // caption (optional)
            if (caption != null && !caption.isBlank()) {
                String truncatedCaption = truncateUtf8(caption, MAX_CAPTION_LENGTH);
                baos.write((dd + boundary + crlf).getBytes(StandardCharsets.UTF_8));
                baos.write(("Content-Disposition: form-data; name=\"caption\"" + crlf).getBytes(StandardCharsets.UTF_8));
                baos.write(("Content-Type: text/plain; charset=UTF-8" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
                baos.write(truncatedCaption.getBytes(StandardCharsets.UTF_8));
                baos.write(crlf.getBytes(StandardCharsets.UTF_8));

                // parse_mode
                baos.write((dd + boundary + crlf).getBytes(StandardCharsets.UTF_8));
                baos.write(("Content-Disposition: form-data; name=\"parse_mode\"" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
                baos.write(("HTML" + crlf).getBytes(StandardCharsets.UTF_8));
            }

            // End boundary
            baos.write((dd + boundary + dd + crlf).getBytes(StandardCharsets.UTF_8));

            return baos.toByteArray();

        } catch (Exception e) {
            throw new RuntimeException("Multipart body olusturma hatasi", e);
        }
    }

    /**
     * String'i belirtilen karakter limitine keser.
     * Telegram Bot API karakter bazli limit uygular (byte degil).
     *
     * @param text metin
     * @param maxChars maksimum karakter sayisi
     * @return kesik metin (veya orijinal)
     */
    private String truncateUtf8(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;

        // 3 karakter birak "..." icin
        int limit = maxChars - 3;
        // Surrogate pair bolunmesini onle (emoji guvenli kesim)
        if (limit > 0 && (Character.isHighSurrogate(text.charAt(limit - 1))
                || Character.isLowSurrogate(text.charAt(limit - 1)))) {
            limit--;
        }
        log.warn("[TELEGRAM-CLIENT] Mesaj kesildi: {} karakter -> {} karakter", text.length(), maxChars);
        return text.substring(0, limit) + "...";
    }

    /**
     * Hata mesajlarindan bot token'i maskeler.
     * Exception message'larinda URL sizmesini onler.
     */
    private String maskTokenInMessage(String message) {
        if (message == null) return "";
        String token = config.getBot().getToken();
        if (token != null && !token.isBlank() && message.contains(token)) {
            return message.replace(token, "***MASKED***");
        }
        return message;
    }

    /**
     * JSON string icin ozel karakterleri escape eder.
     * RFC 8259 §7: U+0000-U+001F kontrol karakterleri dahil.
     */
    private String escapeJson(String text) {
        if (text == null) return "";
        var sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"'  -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
