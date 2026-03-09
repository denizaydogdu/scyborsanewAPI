package com.scyborsa.api.service;

import com.scyborsa.api.config.TelegramConfig;
import com.scyborsa.api.service.telegram.TelegramSendService;
import com.scyborsa.api.utils.BistTradingCalendar;
import com.scyborsa.api.utils.ProfileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Screener tarama sonuclarini Telegram'a gonderen scheduled job.
 *
 * <p>{@link BistGunlukTaramalarJob}'dan 35 saniye sonra calisir (5dk interval).
 * Tarama job'u :00 saniyede, bu job :35 saniyede tetiklenir; boylece
 * yeni tarama sonuclari DB'ye yazildiktan sonra Telegram'a gonderilir.</p>
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

    private final TelegramSendService telegramSendService;
    private final ProfileUtils profileUtils;
    private final TelegramConfig telegramConfig;

    /**
     * Screener taramalarindan 35 saniye sonra calisir (5dk interval).
     *
     * <p>Zamanlama: {@code 35 0/5 9-18 * * MON-FRI} (Europe/Istanbul)</p>
     * <ul>
     *   <li>Screener job: :00 saniyede</li>
     *   <li>Telegram job: :35 saniyede (35sn offset)</li>
     * </ul>
     */
    @Scheduled(cron = "35 0/5 9-18 * * MON-FRI", zone = "Europe/Istanbul")
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

            log.info("[TELEGRAM-JOB] Telegram gonderim basladi");
            telegramSendService.sendPendingMessages();
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
