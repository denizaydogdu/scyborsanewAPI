package com.scyborsa.api.service.job;

import com.scyborsa.api.enums.ScreenerTimesEnum;
import com.scyborsa.api.service.screener.ScreenerExecutionService;
import com.scyborsa.api.utils.BistTradingCalendar;
import com.scyborsa.api.utils.ProfileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

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

    /** Istanbul zaman dilimi sabiti. */
    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");

    /**
     * SC uyumluluk: 5dk cron'un tetikleyip SC'de aktif olmayan zamanlar.
     * SC'de 10:00 ve 10:10 taramasi yok (scheduledScreener times listesinde bulunmuyor).
     */
    private static final Set<LocalTime> SC_SKIP_TIMES = Set.of(
            LocalTime.of(10, 0),
            LocalTime.of(10, 10)
    );

    /** Screener tarama orkestrasyon servisi. */
    private final ScreenerExecutionService screenerExecutionService;

    /** Spring profil kontrol yardimcisi. */
    private final ProfileUtils profileUtils;

    /**
     * SC projesi ile uyumlu zamanlarda screener tarama tetikleyicisi.
     *
     * <p>Zamanlamalar SC ile birebir eslestirilmistir:</p>
     * <ul>
     *   <li>Acilis: 09:56, 10:01 (off-cycle)</li>
     *   <li>Duzeni: 10:05-17:55 arasi 5dk aralik (10:00 ve 10:10 haric)</li>
     *   <li>Kapanis: 17:59, 18:01, 18:06, 18:12 (off-cycle)</li>
     * </ul>
     *
     * <p>Guard clause'lar sirasiyla:
     * (1) prod profil kontrolu,
     * (2) BIST tatil kontrolu,
     * (3) SC skip times kontrolu,
     * (4) {@link ScreenerTimesEnum} ile islem saati eslestirmesi.</p>
     *
     * <p>Tum hatalar yakalanir ve loglanir; exception yukari firlatilmaz (ADR-009).</p>
     */
    @Schedules({
            // Açılış taramaları (SC off-cycle)
            @Scheduled(cron = "0 56 9 * * MON-FRI", zone = "Europe/Istanbul"),
            @Scheduled(cron = "0 1 10 * * MON-FRI", zone = "Europe/Istanbul"),
            // 10:05-17:55 arası 5dk aralık (SC ile uyumlu, 10:00 ve 10:10 method içinde skip)
            @Scheduled(cron = "0 0/5 10-17 * * MON-FRI", zone = "Europe/Istanbul"),
            // Kapanış taramaları (SC off-cycle)
            @Scheduled(cron = "0 59 17 * * MON-FRI", zone = "Europe/Istanbul"),
            @Scheduled(cron = "0 1 18 * * MON-FRI", zone = "Europe/Istanbul"),
            @Scheduled(cron = "0 6 18 * * MON-FRI", zone = "Europe/Istanbul"),
            @Scheduled(cron = "0 12 18 * * MON-FRI", zone = "Europe/Istanbul")
    })
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

            LocalTime now = LocalTime.now(ISTANBUL_ZONE).withSecond(0).withNano(0);

            // SC uyumluluk: 10:00 ve 10:10 SC'de aktif değil
            if (SC_SKIP_TIMES.contains(now)) {
                log.debug("[SCREENER-JOB] SC'de aktif olmayan zaman, atlanıyor: {}", now);
                return;
            }

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
