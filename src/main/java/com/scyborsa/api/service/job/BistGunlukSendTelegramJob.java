package com.scyborsa.api.service.job;

import com.scyborsa.api.config.TelegramConfig;
import com.scyborsa.api.service.telegram.TelegramSendService;
import com.scyborsa.api.utils.BistTradingCalendar;
import com.scyborsa.api.utils.ProfileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Screener tarama sonuclarini Telegram'a gonderen scheduled job.
 *
 * <p>SC projesi ile uyumlu 12 sabit zaman noktasinda tetiklenir.
 * Her tetiklemede sabah/ogleden sonra session bilgisi hesaplanip
 * {@link TelegramSendService}'e boolean flag olarak iletilir.</p>
 *
 * <p>Guard clause'lar:
 * (1) prod profil kontrolu,
 * (2) Telegram aktiflik kontrolu,
 * (3) BIST tatil kontrolu.</p>
 *
 * <p>Tum hatalar yakalanir ve loglanir; exception yukari firlatilmaz (ADR-009).</p>
 *
 * @see TelegramSendService
 * @see BistGunlukTaramalarJob
 * @see TelegramConfig
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BistGunlukSendTelegramJob {

    /** Istanbul saat dilimi. */
    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");

    /** Telegram mesaj gonderim servisi (dedup + batch). */
    private final TelegramSendService telegramSendService;

    /** Spring profil kontrol yardimcisi. */
    private final ProfileUtils profileUtils;

    /** Telegram yapilandirma ayarlari. */
    private final TelegramConfig telegramConfig;

    /**
     * SC projesi ile uyumlu 12 sabit zaman noktasinda Telegram gonderimi yapar.
     *
     * <p>Zamanlamalar (35sn offset — tarama job'larindan sonra):</p>
     * <ul>
     *   <li>Sabah seansi: 09:56, 10:01, 10:30, 11:00</li>
     *   <li>Ogleden sonra seansi: 15:00, 16:00, 17:00, 17:15, 17:30, 17:45, 18:01, 18:06</li>
     * </ul>
     */
    @Schedules({
            @Scheduled(cron = "35 56 9 * * MON-FRI", zone = "Europe/Istanbul"),
            @Scheduled(cron = "35 1 10 * * MON-FRI", zone = "Europe/Istanbul"),
            @Scheduled(cron = "35 30 10 * * MON-FRI", zone = "Europe/Istanbul"),
            @Scheduled(cron = "35 0 11 * * MON-FRI", zone = "Europe/Istanbul"),
            @Scheduled(cron = "35 0 15 * * MON-FRI", zone = "Europe/Istanbul"),
            @Scheduled(cron = "35 0 16 * * MON-FRI", zone = "Europe/Istanbul"),
            @Scheduled(cron = "35 0 17 * * MON-FRI", zone = "Europe/Istanbul"),
            @Scheduled(cron = "35 15 17 * * MON-FRI", zone = "Europe/Istanbul"),
            @Scheduled(cron = "35 30 17 * * MON-FRI", zone = "Europe/Istanbul"),
            @Scheduled(cron = "35 45 17 * * MON-FRI", zone = "Europe/Istanbul"),
            @Scheduled(cron = "35 1 18 * * MON-FRI", zone = "Europe/Istanbul"),
            @Scheduled(cron = "35 6 18 * * MON-FRI", zone = "Europe/Istanbul")
    })
    public void runTelegramSend() {
        try {
            if (!profileUtils.isProdProfile()) {
                log.debug("[TELEGRAM-JOB] Prod degil, atlaniyor");
                return;
            }
            if (!telegramConfig.isEnabled()) {
                log.debug("[TELEGRAM-JOB] Telegram devre disi, atlaniyor");
                return;
            }
            if (!BistTradingCalendar.isNotOffDay()) {
                log.debug("[TELEGRAM-JOB] Tatil gunu, atlaniyor");
                return;
            }

            LocalTime currentTime = LocalTime.now(ISTANBUL_ZONE);
            boolean isMorningSession = currentTime.isBefore(LocalTime.of(11, 0));
            boolean isAfternoonSession = !currentTime.isBefore(LocalTime.of(15, 0));

            log.info("[TELEGRAM-JOB] Telegram gonderim basladi | Saat: {} | Sabah: {} | Ogleden sonra: {}",
                    currentTime, isMorningSession, isAfternoonSession);
            telegramSendService.sendPendingMessages(isMorningSession, isAfternoonSession);
            log.info("[TELEGRAM-JOB] Telegram gonderim tamamlandi");

        } catch (Exception e) {
            log.error("[TELEGRAM-JOB] Telegram gonderim hatasi", e);
        }
    }

    /**
     * Her gun 09:00'da session tracking set'lerini sifirlar.
     *
     * <p>Zamanlama: {@code 0 0 9 * * MON-FRI} (Europe/Istanbul)</p>
     */
    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Europe/Istanbul")
    public void resetDailyState() {
        try {
            if (!profileUtils.isProdProfile()) {
                log.debug("[TELEGRAM-JOB] Reset: Prod degil, atlaniyor");
                return;
            }
            if (!telegramConfig.isEnabled()) {
                log.debug("[TELEGRAM-JOB] Reset: Telegram devre disi, atlaniyor");
                return;
            }
            if (!BistTradingCalendar.isNotOffDay()) {
                log.debug("[TELEGRAM-JOB] Reset: Tatil gunu, atlaniyor");
                return;
            }

            telegramSendService.resetDailyState();
            log.info("[TELEGRAM-JOB] Gunluk state sifirlandi");
        } catch (Exception e) {
            log.error("[TELEGRAM-JOB] Reset hatasi", e);
        }
    }
}
