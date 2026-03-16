package com.scyborsa.api.service.telegram;

import com.scyborsa.api.config.TelegramConfig;
import com.scyborsa.api.model.screener.ScreenerResultModel;
import com.scyborsa.api.repository.ScreenerResultRepository;
import com.scyborsa.api.service.ai.VelzonAiService;
import com.scyborsa.api.service.chart.ChartScreenshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Telegram mesaj gonderim servisi.
 *
 * <p>Screener tarama sonuclarini DB'den okur, hisse bazli gruplar,
 * {@link TelegramMessageFormatter} ile formatlar ve {@link TelegramClient}
 * ile Telegram'a gonderir.</p>
 *
 * <p><b>2-Session Sistemi:</b></p>
 * <ul>
 *   <li>Sabah (09:00-11:00): sentStocksMorning set'i</li>
 *   <li>Ogleden sonra (15:00-18:30): sentStocksAfternoon set'i</li>
 *   <li>Ara donem (11:00-15:00): sabah set'ini kullanir</li>
 * </ul>
 *
 * <p><b>Dedup mekanizmasi:</b> In-memory Set (performans) + DB telegramSent flag (kalicilik).
 * Restart sonrasi in-memory set kaybolur ama findTodayUnsent() zaten gonderilenler icermez.</p>
 *
 * @see TelegramClient
 * @see TelegramMessageFormatter
 * @see com.scyborsa.api.service.job.BistGunlukSendTelegramJob
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramSendService {

    /** Istanbul saat dilimi. */
    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");

    /** Gruplanmis mesaj icin minimum tarama sonucu esigi. */
    private static final int GROUPED_THRESHOLD = 2;

    /** Screener tarama sonuclari repository'si. */
    private final ScreenerResultRepository resultRepository;

    /** Telegram Bot API HTTP istemcisi. */
    private final TelegramClient telegramClient;

    /** Telegram bot yapilandirma bilgileri. */
    private final TelegramConfig telegramConfig;

    /** Telegram mesaj formatlayicisi. */
    private final TelegramMessageFormatter messageFormatter;

    /** Velzon AI analiz servisi (opsiyonel — yoksa AI yorum atlanir). */
    @Autowired(required = false)
    private VelzonAiService velzonAiService;

    /** Chart screenshot servisi (opsiyonel — yoksa text fallback yapilir, ADR-017). */
    @Autowired(required = false)
    private ChartScreenshotService chartScreenshotService;

    // ==================== 2-SESSION DEDUP SET'LERİ ====================

    /** Sabah session (09:00-11:00) gonderilen hisseler. */
    private final Set<String> sentStocksMorning = ConcurrentHashMap.newKeySet();

    /** Ogleden sonra session (15:00-18:30) gonderilen hisseler. */
    private final Set<String> sentStocksAfternoon = ConcurrentHashMap.newKeySet();

    /** AI yorum dedup: gun icinde 1 kez. */
    private final Set<String> sentAiCommentsToday = ConcurrentHashMap.newKeySet();

    /** Concurrent calismayi onleyen guard. */
    private final AtomicBoolean sending = new AtomicBoolean(false);

    // ==================== SESSION ENUM ====================

    /** Telegram gonderim session turleri (sabah, ogleden sonra, ara donem). */
    private enum Session { MORNING, AFTERNOON, GAP }

    // ==================== PUBLIC API ====================

    /**
     * Bekleyen Telegram mesajlarini gonderir.
     *
     * <p>Ana akis:</p>
     * <ol>
     *   <li>findTodayUnsent() ile gonderilmemis kayitlari al</li>
     *   <li>Session bazli dedup filtresi uygula</li>
     *   <li>stockName bazli grupla</li>
     *   <li>Her grup icin mesaj formatla ve gonder</li>
     *   <li>DB'de telegramSent=true isaretle</li>
     * </ol>
     */
    public void sendPendingMessages() {
        if (!sending.compareAndSet(false, true)) {
            log.warn("[TELEGRAM-SEND] Onceki gonderim devam ediyor, atlaniyor");
            return;
        }
        try {
            doSendPendingMessages();
        } finally {
            sending.set(false);
        }
    }

    /**
     * Gercek gonderim mantigi. Concurrency guard sendPendingMessages()'da.
     */
    private void doSendPendingMessages() {
        LocalDate today = LocalDate.now(ISTANBUL_ZONE);
        LocalTime now = LocalTime.now(ISTANBUL_ZONE);
        Session session = determineSession(now);
        Set<String> sentStocks = getSentStocksForSession(session);

        log.info("[TELEGRAM-SEND] Gonderim basliyor | Session: {} | Saat: {}", session, now);

        // 1. DB'den gonderilmemis kayitlari al
        List<ScreenerResultModel> unsent = resultRepository.findTodayUnsent(today);
        if (unsent.isEmpty()) {
            log.debug("[TELEGRAM-SEND] Gonderilecek kayit yok");
            return;
        }

        log.info("[TELEGRAM-SEND] {} gonderilmemis kayit bulundu", unsent.size());

        // 2. Session bazli dedup filtresi
        List<ScreenerResultModel> filtered = unsent.stream()
                .filter(r -> !sentStocks.contains(r.getStockName()))
                .toList();

        if (filtered.isEmpty()) {
            log.debug("[TELEGRAM-SEND] Tum hisseler bu session'da zaten gonderildi");
            return;
        }

        // 3. stockName bazli grupla
        Map<String, List<ScreenerResultModel>> grouped = filtered.stream()
                .collect(Collectors.groupingBy(ScreenerResultModel::getStockName, LinkedHashMap::new, Collectors.toList()));

        log.info("[TELEGRAM-SEND] {} unique hisse grupandi", grouped.size());

        // 4. Her grup icin mesaj gonder
        int totalSent = 0;
        int groupedCount = 0;
        int singleCount = 0;

        for (Map.Entry<String, List<ScreenerResultModel>> entry : grouped.entrySet()) {
            String stockName = entry.getKey();
            List<ScreenerResultModel> results = entry.getValue();

            try {
                // Fiyat validation
                ScreenerResultModel latest = results.get(results.size() - 1);
                if (latest.getPrice() == null || latest.getPrice() <= 0) {
                    log.warn("[TELEGRAM-SEND] Gecersiz fiyat: {} | Price: {} | SKIP",
                            stockName, latest.getPrice());
                    continue;
                }

                // Mesaj formatla
                String message;
                boolean isGrouped = results.size() >= GROUPED_THRESHOLD;
                if (isGrouped) {
                    message = messageFormatter.formatGroupedStockMessage(stockName, results);
                } else {
                    message = messageFormatter.formatSingleStockMessage(stockName, results.get(0));
                }

                // null = KGS filtresi tarafindan engellendi
                if (message == null) {
                    continue;
                }

                // Screenshot denemesi — basarisizsa text fallback
                byte[] screenshot = captureScreenshotIfEnabled(stockName);
                boolean sent;
                if (screenshot != null && screenshot.length > 0) {
                    String caption = buildPhotoCaption(stockName, results);
                    sent = telegramClient.sendPhoto(screenshot, caption);
                    if (sent) {
                        log.info("[TELEGRAM-SEND] {} screenshot ile gonderildi", stockName);
                    } else {
                        log.warn("[TELEGRAM-SEND] {} screenshot gonderilemedi, text fallback", stockName);
                        sent = telegramClient.sendHtmlMessage(message);
                    }
                } else {
                    sent = telegramClient.sendHtmlMessage(message);
                }
                if (!sent) {
                    log.warn("[TELEGRAM-SEND] {} icin Telegram gonderilemedi, DB atlanıyor", stockName);
                    continue;
                }

                // Counter'i basarili gonderimden SONRA artir
                if (isGrouped) {
                    groupedCount++;
                } else {
                    singleCount++;
                }

                // Session set'ine ONCE ekle — DB hatasi olursa duplicate gonderim engellenir.
                // DB write sonraki turda findTodayUnsent() ile tekrar denenebilir.
                sentStocks.add(stockName);
                totalSent++;

                // DB'de isaretle (basarili gonderimden sonra)
                try {
                    List<Long> ids = results.stream()
                            .map(ScreenerResultModel::getId)
                            .toList();
                    int distinctCount = (int) results.stream()
                            .map(ScreenerResultModel::getScreenerName)
                            .distinct().count();
                    String commonNames = results.stream()
                            .map(ScreenerResultModel::getScreenerName)
                            .filter(java.util.Objects::nonNull)
                            .distinct()
                            .collect(Collectors.joining(","));
                    if (commonNames.length() > 500) {
                        commonNames = commonNames.substring(0, 497) + "...";
                    }

                    LocalDateTime sentTime = ZonedDateTime.now(ISTANBUL_ZONE).toLocalDateTime();
                    resultRepository.bulkMarkTelegramSent(ids, sentTime, distinctCount, commonNames);
                } catch (Exception dbEx) {
                    log.error("[TELEGRAM-SEND] {} icin DB isaretleme basarisiz (Telegram gonderildi): {}",
                            stockName, dbEx.getMessage());
                    // sentStocks zaten eklendi — duplicate gonderim engellendi
                }

                // Rate limiting
                Thread.sleep(telegramConfig.getSendRateLimitMs());

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[TELEGRAM-SEND] Gonderim interrupted, kalan {} hisse islenmedi",
                        grouped.size() - totalSent);
                break;
            } catch (Exception e) {
                log.error("[TELEGRAM-SEND] {} icin mesaj gonderilemedi: {}", stockName, e.getMessage(), e);
                // sentStocks'a EKLENMEDI — sonraki turda tekrar denenir
            }
        }

        log.info("[TELEGRAM-SEND] Gonderim tamamlandi | Toplam: {} | Gruplanmis: {} | Tekil: {}",
                totalSent, groupedCount, singleCount);

        // 5. AI yorumlarini ana gonderim tamamlandiktan SONRA isle
        // Ana Telegram mesajlarini bloklamaz (60s timeout riski yok)
        sendAiCommentsForBatch(grouped);
    }

    /**
     * Tum basarili gonderimler icin AI yorumlarini toplu isle.
     *
     * <p>Ana Telegram gonderim dongusunden SONRA calisir,
     * boylece senkron AI cagrilari (60s timeout) ana mesajlari bloklamaz.</p>
     *
     * @param grouped hisse bazli gruplanmis tarama sonuclari
     */
    private void sendAiCommentsForBatch(Map<String, List<ScreenerResultModel>> grouped) {
        if (velzonAiService == null || !velzonAiService.isEnabled()) return;
        if (!telegramConfig.getAi().isEnabled()) return;

        int sizeBefore = sentAiCommentsToday.size();
        for (Map.Entry<String, List<ScreenerResultModel>> entry : grouped.entrySet()) {
            sendAiCommentForStock(entry.getKey(), entry.getValue());
        }
        int aiSent = sentAiCommentsToday.size() - sizeBefore;

        if (aiSent > 0) {
            log.info("[TELEGRAM-SEND] AI yorum gonderimi tamamlandi | Toplam: {}", aiSent);
        }
    }

    /**
     * Gunluk dedup set'lerini sifirlar.
     * Her gun 09:00'da BistGunlukSendTelegramJob tarafindan cagirilir.
     */
    public void resetDailyState() {
        // ConcurrentHashMap.clear() thread-safe — lock gereksiz.
        // Lock kullanmak gonderim sirasinda reset'in sessizce atlanmasina neden oluyordu.
        int morningCount = sentStocksMorning.size();
        int afternoonCount = sentStocksAfternoon.size();
        int aiCount = sentAiCommentsToday.size();

        sentStocksMorning.clear();
        sentStocksAfternoon.clear();
        sentAiCommentsToday.clear();

        log.info("[TELEGRAM-SEND] Set'ler sifirlandi | Sabah: {} | Ogleden sonra: {} | AI: {}",
                morningCount, afternoonCount, aiCount);
    }

    /**
     * Belirtilen hisse icin AI yorumunun bugun gonderilip gonderilmedigini kontrol eder.
     *
     * @param stockName hisse kodu
     * @return bugun zaten gonderildiyse true
     */
    public boolean isAiCommentSentToday(String stockName) {
        return sentAiCommentsToday.contains(stockName);
    }

    /**
     * AI yorumunu Telegram'a gonderir.
     *
     * <p>Dedup: gunde 1 kez/hisse (sentAiCommentsToday).
     * AI topic tanimliysa oraya, yoksa fallbackToMain aktifse ana chat'e gonderir.</p>
     *
     * @param stockName hisse kodu
     * @param aiComment AI tarafindan uretilen yorum metni
     */
    public void sendAiComment(String stockName, String aiComment) {
        // Atomic add — TOCTOU race condition onlenir (contains+add yerine tek islem)
        if (!sentAiCommentsToday.add(stockName)) {
            log.debug("[TELEGRAM-SEND] AI yorum zaten gonderildi: {}", stockName);
            return;
        }

        String html = formatAiComment(stockName, aiComment);

        int topicId = telegramConfig.getAi().getTopicId();
        boolean sent;
        if (topicId > 0) {
            sent = telegramClient.sendHtmlMessageToTopic(html, topicId);
        } else if (telegramConfig.getAi().isFallbackToMain()) {
            sent = telegramClient.sendHtmlMessage(html);
        } else {
            log.debug("[TELEGRAM-SEND] AI topic tanimli degil ve fallback kapali: {}", stockName);
            sentAiCommentsToday.remove(stockName); // Rollback — gonderilmedi
            return;
        }

        if (sent) {
            log.info("[TELEGRAM-SEND] AI yorum gonderildi: {}", stockName);
        } else {
            sentAiCommentsToday.remove(stockName); // Rollback — tekrar denenebilir
            log.warn("[TELEGRAM-SEND] AI yorum gonderilemedi: {}", stockName);
        }
    }

    // ==================== PRIVATE ====================

    /**
     * AI yorum mesajini HTML formatlar.
     *
     * @param stockName hisse kodu
     * @param aiComment AI yorum metni
     * @return HTML formatli mesaj
     */
    private String formatAiComment(String stockName, String aiComment) {
        final int TELEGRAM_MAX_LENGTH = 4096;
        // Template sabit kisim overhead'i (~130 karakter: HTML taglari + disclaimer + zaman)
        final int TEMPLATE_OVERHEAD = 150;

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("HH:mm | dd MMMM yyyy",
                new Locale("tr"));
        String timeStr = ZonedDateTime.now(ISTANBUL_ZONE).format(dtf);

        // AI yaniti external veri — HTML escape zorunlu (F/K>15 gibi iceriklerde parse hatasi onlenir)
        String escapedComment = aiComment
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");

        // Yorum truncation'i template'den ONCE yapilir — HTML tag'larin ortasindan kesilmesi onlenir
        int maxCommentLength = TELEGRAM_MAX_LENGTH - TEMPLATE_OVERHEAD - stockName.length() - timeStr.length();
        if (escapedComment.length() > maxCommentLength) {
            escapedComment = escapedComment.substring(0, maxCommentLength - 3) + "...";
        }

        return String.format(
                "<b>AI Degerlendirme: %s</b>\n\n%s\n\n<i>%s</i>\n<i>Bu yorum yapay zeka tarafindan uretilmistir, yatirim tavsiyesi degildir.</i>",
                stockName, escapedComment, timeStr);
    }

    /**
     * Hisse icin AI yorumu olusturup AI topic'e gonderir.
     *
     * <p>Graceful degradation: tum hatalar yakalanir.
     * AI cagrılari arası 1 saniye beklenir.</p>
     *
     * @param stockName hisse kodu
     * @param results hisseye ait tarama sonuclari
     */
    private void sendAiCommentForStock(String stockName, List<ScreenerResultModel> results) {
        if (velzonAiService == null || !velzonAiService.isEnabled()) return;
        if (!telegramConfig.getAi().isEnabled()) return;
        if (sentAiCommentsToday.contains(stockName)) return;

        try {
            // En son tarama sonucu en guncel fiyati tasir
            ScreenerResultModel latest = results.get(results.size() - 1);
            Double price = latest.getPrice();
            Double change = latest.getPercentage();
            List<String> screenerNames = results.stream()
                    .map(ScreenerResultModel::getScreenerName)
                    .distinct()
                    .toList();

            String comment = velzonAiService.analyzeStock(stockName, price, change, screenerNames);
            if (comment != null && !comment.isBlank()) {
                sendAiComment(stockName, comment);
            }

            // Rate limit: AI cagrilari arasi 1 saniye bekle
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("[TELEGRAM-SEND] AI yorum hatasi ({}): {}", stockName, e.getMessage());
        }
    }

    /**
     * Screenshot servisinden gorsel almayi dener.
     *
     * <p>Graceful degradation: servis yoksa, devre disiysa veya hata olursa {@code null} doner.</p>
     *
     * @param stockName hisse kodu
     * @return PNG binary veri veya {@code null}
     */
    private byte[] captureScreenshotIfEnabled(String stockName) {
        if (chartScreenshotService == null || !chartScreenshotService.isEnabled()) return null;
        try {
            return chartScreenshotService.captureChartScreenshot(stockName);
        } catch (Exception e) {
            log.warn("[TELEGRAM-SEND] Screenshot hatasi ({}): {}", stockName, e.getMessage());
            return null;
        }
    }

    /**
     * Telegram sendPhoto icin kisa caption olusturur.
     *
     * <p>Telegram photo caption limiti 1024 karakter.
     * Hisse adi, fiyat, degisim yuzdesi ve tarama isimlerini icerir.</p>
     *
     * @param stockName hisse kodu
     * @param results hisseye ait tarama sonuclari
     * @return HTML formatli caption metni
     */
    private String buildPhotoCaption(String stockName, List<ScreenerResultModel> results) {
        ScreenerResultModel latest = results.get(results.size() - 1);
        String screenerNames = results.stream()
                .map(ScreenerResultModel::getScreenerName)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining(", "));

        StringBuilder sb = new StringBuilder();
        sb.append("<b>").append(stockName).append("</b>");
        if (latest.getPrice() != null) {
            sb.append(" | ").append(String.format("%.2f TL", latest.getPrice()));
        }
        if (latest.getPercentage() != null) {
            String sign = latest.getPercentage() >= 0 ? "+" : "";
            sb.append(" (").append(sign).append(String.format("%.2f%%", latest.getPercentage())).append(")");
        }
        if (!screenerNames.isEmpty()) {
            // Screener isimlerini 800 karakterle sinirla — HTML tag kesilmesini onle
            String safeNames = screenerNames.length() > 800 ? screenerNames.substring(0, 797) + "..." : screenerNames;
            sb.append("\n").append(safeNames);
        }
        // Telegram caption limiti: 1024 karakter
        if (sb.length() > 1024) {
            sb.setLength(1021);
            sb.append("...");
        }
        return sb.toString();
    }

    /**
     * Mevcut saate gore session belirler.
     *
     * @param now Istanbul zamani
     * @return session turu
     */
    private Session determineSession(LocalTime now) {
        if (now.isBefore(LocalTime.of(9, 0))) {
            return Session.GAP; // Pre-market: morning set'ini kirletme
        } else if (now.isBefore(LocalTime.of(11, 0))) {
            return Session.MORNING;
        } else if (!now.isBefore(LocalTime.of(15, 0))) {
            return Session.AFTERNOON;
        }
        return Session.GAP;
    }

    /**
     * Session'a gore uygun dedup set'ini dondurur.
     *
     * @param session aktif session
     * @return ilgili ConcurrentHashMap set'i
     */
    private Set<String> getSentStocksForSession(Session session) {
        return switch (session) {
            case MORNING -> sentStocksMorning;
            case AFTERNOON -> sentStocksAfternoon;
            // GAP (11:00-14:59): sabah set'ini kullanir → sabah gonderilen hisseler
            // GAP'ta da bastırılır. Ogleden sonra (AFTERNOON) yeni sinyal gelirse
            // sentStocksAfternoon bos oldugundan tekrar gonderilir. Kasitli davranis.
            case GAP -> sentStocksMorning;
        };
    }
}
