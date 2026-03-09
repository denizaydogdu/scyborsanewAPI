package com.scyborsa.api.service;

import com.scyborsa.api.enums.ScreenerTimesEnum;
import com.scyborsa.api.service.screener.ScreenerExecutionService;
import com.scyborsa.api.utils.BistTradingCalendar;
import com.scyborsa.api.utils.ProfileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;

/**
 * BIST işlem saatlerinde periyodik screener taramalarını çalıştıran scheduled job.
 *
 * <p>5 dakikada bir tetiklenir (09:55-18:15 MON-FRI). {@link ScreenerTimesEnum} ile
 * ince zaman filtresi uygulayarak 7 farklı tarama türünü çalıştırır.</p>
 *
 * <p>Eski projedeki 120+ ayrı {@code @Scheduled} method → tek method + time-dispatch
 * mimarisine dönüştürülmüştür.</p>
 *
 * @see ScreenerExecutionService
 * @see ScreenerTimesEnum
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BistGunlukTaramalarJob {

    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");

    private final ScreenerExecutionService screenerExecutionService;
    private final ProfileUtils profileUtils;

    /**
     * 5 dakikada bir çalışan screener tarama tetikleyicisi.
     *
     * <p>Guard clause'lar sırasıyla:
     * (1) prod profil kontrolü,
     * (2) BIST tatil kontrolü,
     * (3) {@link ScreenerTimesEnum} ile işlem saati eşleştirmesi.</p>
     *
     * <p>Tüm hatalar yakalanır ve loglanır; exception yukarı fırlatılmaz (ADR-009).</p>
     */
    @Scheduled(cron = "0 0/5 9-18 * * MON-FRI", zone = "Europe/Istanbul")
    public void runScreeners() {
        try {
            if (!profileUtils.isProdProfile()) {
                log.debug("[SCREENER-JOB] Prod değil, tarama atlanıyor");
                return;
            }
            if (!BistTradingCalendar.isNotOffDay()) {
                log.debug("[SCREENER-JOB] Tatil günü, tarama atlanıyor");
                return;
            }

            LocalTime now = LocalTime.now(ISTANBUL_ZONE);
            ScreenerTimesEnum timeSlot = ScreenerTimesEnum.fromTime(now);
            if (timeSlot == null || !timeSlot.isMarketHours()) {
                log.debug("[SCREENER-JOB] İşlem saati dışı, tarama atlanıyor: {}", now);
                return;
            }

            log.info("[SCREENER-JOB] Tarama başlıyor: {}", timeSlot.getTimeStr());
            screenerExecutionService.executeAllScreeners(timeSlot);
            log.info("[SCREENER-JOB] Tarama tamamlandı: {}", timeSlot.getTimeStr());
        } catch (Exception e) {
            log.error("[SCREENER-JOB] Tarama hatası", e);
        }
    }
}
