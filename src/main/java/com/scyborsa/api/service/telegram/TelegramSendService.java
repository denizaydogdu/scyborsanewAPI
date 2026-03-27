package com.scyborsa.api.service.telegram;

import com.scyborsa.api.config.TelegramConfig;
import com.scyborsa.api.model.screener.ScreenerResultModel;
import com.scyborsa.api.repository.ScreenerResultRepository;
import com.scyborsa.api.service.ai.VelzonAiService;
import com.scyborsa.api.service.chart.ChartScreenshotService;
import com.scyborsa.api.service.telegram.infographic.StockCardData;
import com.scyborsa.api.service.telegram.infographic.StockCardRenderer;
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
 *   <li>Sabah (isMorningSession=true): sentStocksMorning set'i</li>
 *   <li>Ogleden sonra (isAfternoonSession=true): sentStocksAfternoon set'i</li>
 *   <li>Ara donem (ikisi de false): sabah set'ini kullanir</li>
 * </ul>
 *
 * <p><b>Dedup mekanizmasi:</b> In-memory Set (performans) + zaman pencereli DB sorgusu.
 * Son 10 dakikadaki gonderilmemis sonuclar alinir, session bazli dedup uygulanir.</p>
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

    /** Infografik kart render servisi (opsiyonel — yoksa text fallback yapilir). */
    @Autowired(required = false)
    private StockCardRenderer stockCardRenderer;

    // ==================== 2-SESSION DEDUP SET'LERİ ====================

    /** Sabah session (09:00-11:00) gonderilen hisseler. */
    private final Set<String> sentStocksMorning = ConcurrentHashMap.newKeySet();

    /** Ogleden sonra session (15:00-18:30) gonderilen hisseler. */
    private final Set<String> sentStocksAfternoon = ConcurrentHashMap.newKeySet();

    /** AI yorum dedup: gun icinde 1 kez. */
    private final Set<String> sentAiCommentsToday = ConcurrentHashMap.newKeySet();

    /** Concurrent calismayi onleyen guard. */
    private final AtomicBoolean sending = new AtomicBoolean(false);

    // ==================== PUBLIC API ====================

    /**
     * Bekleyen Telegram mesajlarini gonderir.
     *
     * <p>Ana akis:</p>
     * <ol>
     *   <li>findRecentUnsent() ile son 10 dakikadaki gonderilmemis kayitlari al</li>
     *   <li>Session bazli dedup filtresi uygula</li>
     *   <li>stockName bazli grupla</li>
     *   <li>Her grup icin mesaj formatla ve gonder</li>
     *   <li>DB'de telegramSent=true isaretle</li>
     * </ol>
     *
     * @param isMorningSession sabah seansi mi (saat 11:00 oncesi)
     * @param isAfternoonSession ogleden sonra seansi mi (saat 15:00 sonrasi)
     */
    public void sendPendingMessages(boolean isMorningSession, boolean isAfternoonSession) {
        if (!sending.compareAndSet(false, true)) {
            log.warn("[TELEGRAM-SEND] Onceki gonderim devam ediyor, atlaniyor");
            return;
        }
        try {
            doSendPendingMessages(isMorningSession, isAfternoonSession);
        } finally {
            sending.set(false);
        }
    }

    /**
     * Gercek gonderim mantigi. Concurrency guard sendPendingMessages()'da.
     *
     * @param isMorningSession sabah seansi mi
     * @param isAfternoonSession ogleden sonra seansi mi
     */
    private void doSendPendingMessages(boolean isMorningSession, boolean isAfternoonSession) {
        LocalDate today = LocalDate.now(ISTANBUL_ZONE);
        LocalTime now = LocalTime.now(ISTANBUL_ZONE);
        LocalTime tenMinutesAgo = now.minusMinutes(10);
        Set<String> sentStocks = getSentStocksForSession(isMorningSession, isAfternoonSession);

        log.info("[TELEGRAM-SEND] Gonderim basliyor | Sabah: {} | Ogleden sonra: {} | Saat: {}",
                isMorningSession, isAfternoonSession, now);

        // 1. DB'den son 10 dakikadaki gonderilmemis kayitlari al
        List<ScreenerResultModel> unsent = resultRepository.findRecentUnsent(today, tenMinutesAgo, now);
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

                // Screenshot bir kez çek — her iki path'te kullanılır
                boolean isGrouped = results.size() > 1;
                boolean infographicSent = false;
                boolean screenshotAlreadySent = false;
                byte[] screenshot = captureScreenshotIfEnabled(stockName);

                // --- INFOGRAPHIC + CHART PATH ---
                if (isInfographicEnabled()) {
                    try {
                        // 1. Önce chart screenshot gönder (grafik)
                        if (screenshot != null && screenshot.length > 0) {
                            boolean chartSent = telegramClient.sendPhoto(screenshot,
                                    "📊 " + stockName);
                            if (chartSent) {
                                log.info("[TELEGRAM-SEND] {} chart screenshot gonderildi", stockName);
                                screenshotAlreadySent = true;
                            }
                        }

                        // 2. Sonra infografik kart gönder (veri kartı)
                        StockCardData cardData = messageFormatter.buildStockCardData(stockName, results);
                        byte[] infographic = stockCardRenderer.renderCard(cardData);
                        if (infographic != null && infographic.length > 0) {
                            boolean photoSent = telegramClient.sendPhoto(infographic, "");
                            if (photoSent) {
                                log.info("[TELEGRAM-SEND] {} infografik kart gonderildi", stockName);
                                infographicSent = true;
                            } else {
                                log.warn("[TELEGRAM-SEND] {} infografik gonderilemedi, text fallback", stockName);
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[TELEGRAM-SEND] {} infografik render hatasi, text fallback: {}",
                                stockName, e.getMessage());
                    }
                }

                // --- EXISTING TEXT + SCREENSHOT PATH (fallback) ---
                if (!infographicSent) {
                    // Mesaj formatla
                    String message;
                    if (isGrouped) {
                        message = messageFormatter.formatGroupedStockMessage(stockName, results);
                    } else {
                        message = messageFormatter.formatSingleStockMessage(stockName, results.get(0));
                    }

                    // null = KGS filtresi tarafindan engellendi
                    if (message == null) {
                        continue;
                    }

                    // Screenshot zaten çekildi — tekrar çekme, zaten gönderildiyse tekrar gönderme
                    boolean sent;
                    if (screenshot != null && screenshot.length > 0 && !screenshotAlreadySent) {
                        // Screenshot + tam mesaj ayrı gönder (caption byte limiti sorununu önler)
                        String caption = truncateCaptionToBytes(message, 1024);
                        sent = telegramClient.sendPhoto(screenshot, caption);
                        if (sent) {
                            log.info("[TELEGRAM-SEND] {} screenshot ile gonderildi", stockName);
                            // Caption'a sığmadıysa tam mesajı ayrı gönder
                            byte[] msgBytes = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            if (msgBytes.length > 1024) {
                                telegramClient.sendHtmlMessage(message);
                                log.info("[TELEGRAM-SEND] {} tam mesaj ayri gonderildi", stockName);
                            }
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
                }

                // Counter'i basarili gonderimden SONRA artir
                if (isGrouped) {
                    groupedCount++;
                } else {
                    singleCount++;
                }

                // Hisseler arasi ayirici — bir sonraki hisse oncesi
                telegramClient.sendHtmlMessage("****************************************");

                // Session set'ine ONCE ekle — DB hatasi olursa duplicate gonderim engellenir.
                // DB write sonraki turda findRecentUnsent() ile tekrar denenebilir.
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
        // sendAiCommentsForBatch(grouped);  // AI Telegram devre dışı
    }

    /**
     * Infografik kart ozelliginin aktif olup olmadigini kontrol eder.
     *
     * @return config aktif ve renderer bean mevcutsa {@code true}
     */
    private boolean isInfographicEnabled() {
        return telegramConfig.getInfographic().isEnabled() && stockCardRenderer != null;
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
            if (topicId > 0) {
                telegramClient.sendHtmlMessageToTopic("****************************************", topicId);
            } else {
                telegramClient.sendHtmlMessage("****************************************");
            }
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
     * Session flag'larina gore uygun dedup set'ini dondurur.
     *
     * <p>Sabah seansi (isMorningSession=true) veya ara donem (ikisi de false):
     * sentStocksMorning set'ini kullanir. Ogleden sonra seansi (isAfternoonSession=true):
     * sentStocksAfternoon set'ini kullanir.</p>
     *
     * @param isMorningSession sabah seansi mi
     * @param isAfternoonSession ogleden sonra seansi mi
     * @return ilgili ConcurrentHashMap set'i
     */
    private Set<String> getSentStocksForSession(boolean isMorningSession, boolean isAfternoonSession) {
        if (isAfternoonSession) {
            return sentStocksAfternoon;
        }
        // Sabah seansi VEYA ara donem (11:00-15:00): sabah set'ini kullanir
        return sentStocksMorning;
    }

    /**
     * Telegram photo caption'i UTF-8 byte limitine gore truncate eder.
     *
     * <p>SC uyumlu: Telegram sendPhoto caption limiti 1024 byte (UTF-8).
     * Turkce karakterler 2 byte oldugu icin karakter sayisi degil byte sayisi kontrol edilir.</p>
     *
     * @param text orijinal mesaj metni
     * @param maxBytes maksimum byte limiti (1024)
     * @return truncate edilmis veya orijinal metin
     */
    private String truncateCaptionToBytes(String text, int maxBytes) {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        log.warn("[TELEGRAM-SEND] Caption truncated: {} bytes -> {} bytes", bytes.length, maxBytes);
        // UTF-8 safe truncation: karakter ortasinda kesmemek icin geri git
        int end = maxBytes - 3; // "..." icin yer ayir
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--; // UTF-8 continuation byte'larini atla
        }
        String truncated = new String(bytes, 0, end, java.nio.charset.StandardCharsets.UTF_8);
        // HTML tag ortasinda kesmemek icin son acik '<' varsa onu da kes
        int lastOpenTag = truncated.lastIndexOf('<');
        int lastCloseTag = truncated.lastIndexOf('>');
        if (lastOpenTag > lastCloseTag) {
            // Acik tag var ama kapanmamis — tag'dan once kes
            truncated = truncated.substring(0, lastOpenTag);
        }
        // Acik HTML tag'lari kapat (b, i, code, a)
        String[] tags = {"b", "i", "code", "a"};
        for (String tag : tags) {
            int opens = countOccurrences(truncated, "<" + tag + ">") + countOccurrences(truncated, "<" + tag + " ");
            int closes = countOccurrences(truncated, "</" + tag + ">");
            for (int i = 0; i < opens - closes; i++) {
                truncated += "</" + tag + ">";
            }
        }
        return truncated + "...";
    }

    private int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
