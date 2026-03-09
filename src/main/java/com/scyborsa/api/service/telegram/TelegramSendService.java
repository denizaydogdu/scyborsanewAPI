package com.scyborsa.api.service.telegram;

import com.scyborsa.api.config.TelegramConfig;
import com.scyborsa.api.model.ScreenerResultModel;
import com.scyborsa.api.repository.ScreenerResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
 * @see com.scyborsa.api.service.BistGunlukSendTelegramJob
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramSendService {

    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");
    private static final int GROUPED_THRESHOLD = 2;

    private final ScreenerResultRepository resultRepository;
    private final TelegramClient telegramClient;
    private final TelegramConfig telegramConfig;
    private final TelegramMessageFormatter messageFormatter;

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

                // Telegram'a gonder — basarisizsa DB'ye YAZMA
                boolean sent = telegramClient.sendHtmlMessage(message);
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

    // ==================== PRIVATE ====================

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
