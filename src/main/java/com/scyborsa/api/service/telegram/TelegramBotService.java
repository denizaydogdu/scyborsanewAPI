package com.scyborsa.api.service.telegram;

import com.scyborsa.api.service.enrichment.SirketRaporService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Telegram bot temel analiz servisi.
 *
 * <p>Telegram bot sorguları için şirket raporu oluşturur ve
 * Telegram HTML formatına dönüştürür. Şimdilik Telegram bot
 * webhook entegrasyonu mevcut değildir; sadece servis katmanı hazırdır.</p>
 *
 * <p>Akış:</p>
 * <ol>
 *   <li>Kullanıcı sorgusundan hisse kodu çıkarılır</li>
 *   <li>{@link SirketRaporService#generateRapor(String)} çağrılır</li>
 *   <li>Markdown rapor kısa özet HTML'e dönüştürülür</li>
 * </ol>
 *
 * @see SirketRaporService
 * @see TelegramClient
 */
@Slf4j
@Service
public class TelegramBotService {

    /** Şirket rapor servisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private SirketRaporService sirketRaporService;

    /** Telegram istemcisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private TelegramClient telegramClient;

    /**
     * Telegram bot sorgusunu işler ve HTML formatında yanıt döndürür.
     *
     * <p>Sorgu metninden hisse kodu çıkarılır, şirket raporu üretilir
     * ve kısa bir HTML özet oluşturulur. Rapor üretilemezse hata
     * mesajı döndürülür (graceful degradation).</p>
     *
     * @param query kullanıcı sorgusu (ör: "THYAO", "GARAN analiz")
     * @return Telegram HTML formatında yanıt mesajı
     */
    public String handleBotQuery(String query) {
        log.debug("[TELEGRAM-BOT] Sorgu alındı: query={}", query);

        if (query == null || query.isBlank()) {
            return "<b>Hata:</b> Lütfen bir hisse kodu giriniz.";
        }

        String stockCode = extractStockCode(query);
        if (stockCode == null) {
            return "<b>Hata:</b> Geçerli bir hisse kodu bulunamadı.";
        }

        try {
            if (sirketRaporService == null) {
                return "<b>Hata:</b> Rapor servisi kullanılamıyor.";
            }

            String markdownRapor = sirketRaporService.generateRapor(stockCode);
            return markdownToTelegramHtml(stockCode, markdownRapor);
        } catch (Exception e) {
            log.warn("[TELEGRAM-BOT] Rapor üretilemedi: stockCode={}, hata={}", stockCode, e.getMessage());
            return "<b>Hata:</b> " + escapeHtml(stockCode) + " için rapor üretilemedi.";
        }
    }

    /**
     * Sorgu metninden hisse kodu çıkarır.
     *
     * <p>İlk boşluktan önceki kelimeyi alır ve büyük harfe çevirir.
     * 1-10 karakter büyük harf/rakam formatına uymayanlar reddedilir.</p>
     *
     * @param query kullanıcı sorgusu
     * @return hisse kodu veya {@code null} (geçersiz ise)
     */
    private String extractStockCode(String query) {
        String trimmed = query.trim();
        String firstWord = trimmed.contains(" ") ? trimmed.substring(0, trimmed.indexOf(' ')) : trimmed;
        String upper = firstWord.toUpperCase();

        if (upper.matches("^[A-Z0-9]{1,10}$")) {
            return upper;
        }
        return null;
    }

    /**
     * Markdown rapordan kısa Telegram HTML özeti oluşturur.
     *
     * <p>Tam rapor yerine başlık ve ilk birkaç satırı HTML formatına
     * dönüştürür. Telegram mesaj uzunluk sınırı göz önünde bulundurulur.</p>
     *
     * @param stockCode hisse kodu
     * @param markdown  markdown rapor içeriği
     * @return Telegram HTML formatında kısa özet
     */
    private String markdownToTelegramHtml(String stockCode, String markdown) {
        StringBuilder html = new StringBuilder();
        html.append("<b>📊 ").append(escapeHtml(stockCode)).append(" — Temel Analiz Özeti</b>\n\n");

        // Markdown satırlarından basit HTML çıkarımı
        String[] lines = markdown.split("\n");
        int lineCount = 0;
        for (String line : lines) {
            if (line.startsWith("# ") || line.startsWith("_Rapor tarihi")) {
                continue; // Başlık ve tarih satırını atla
            }
            if (line.startsWith("## ")) {
                html.append("\n<b>").append(line.substring(3).trim()).append("</b>\n");
                lineCount++;
            } else if (line.startsWith("| ") && !line.contains("---")) {
                // Tablo satırını düz metin olarak ekle
                String cleaned = line.replaceAll("\\|", "").trim();
                if (!cleaned.isEmpty()) {
                    html.append("• ").append(cleaned).append("\n");
                    lineCount++;
                }
            } else if (line.startsWith("- ")) {
                html.append("• ").append(line.substring(2).trim()).append("\n");
                lineCount++;
            }

            // Telegram mesaj sınırı koruması
            if (html.length() > 3500 || lineCount > 40) {
                html.append("\n<i>... (rapor kısaltıldı)</i>");
                break;
            }
        }

        return html.toString();
    }

    /**
     * HTML özel karakterlerini escape eder.
     *
     * @param text escape edilecek metin
     * @return HTML-safe metin
     */
    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
