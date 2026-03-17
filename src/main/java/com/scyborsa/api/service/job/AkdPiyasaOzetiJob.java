package com.scyborsa.api.service.job;

import com.scyborsa.api.config.TelegramConfig;
import com.scyborsa.api.dto.enrichment.BrokerageAkdListResponseDto;
import com.scyborsa.api.service.enrichment.BrokerageAkdListService;
import com.scyborsa.api.service.telegram.AkdPiyasaOzetiTelegramBuilder;
import com.scyborsa.api.service.telegram.TelegramClient;
import com.scyborsa.api.utils.BistTradingCalendar;
import com.scyborsa.api.utils.ProfileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.stereotype.Component;

/**
 * AKD Piyasa Özeti Telegram gönderim job'u.
 *
 * <p>SC ile birebir uyumlu 18 sabit zamanda top 5 alıcı ve top 5 satıcı
 * kurumları Telegram'a gönderir.</p>
 *
 * @see AkdPiyasaOzetiTelegramBuilder
 * @see BrokerageAkdListService
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AkdPiyasaOzetiJob {

    /** Telegram mesaj gonderim istemcisi. */
    private final TelegramClient telegramClient;

    /** Telegram yapilandirma ayarlari. */
    private final TelegramConfig telegramConfig;

    /** Spring profil kontrol yardimcisi. */
    private final ProfileUtils profileUtils;

    /** Araci kurum AKD listesi servisi (cache reuse). */
    private final BrokerageAkdListService brokerageAkdListService;

    /** AKD piyasa ozeti Telegram mesaj olusturucu. */
    private final AkdPiyasaOzetiTelegramBuilder builder;

    /**
     * AKD piyasa özeti Telegram gönderimini tetikler.
     *
     * <p>SC ile birebir uyumlu 18 sabit zamanda çalışır:</p>
     * <ul>
     *   <li>10:03, 10:38</li>
     *   <li>11:08, 11:38, 12:08, 12:38, 13:08, 13:38</li>
     *   <li>14:08, 14:38, 15:08, 15:38, 16:08, 16:38</li>
     *   <li>17:08, 17:38, 17:58, 18:08</li>
     * </ul>
     */
    @Schedules({
            @Scheduled(cron = "0 3 10 * * MON-FRI", zone = "Europe/Istanbul"),
            @Scheduled(cron = "0 38 10 * * MON-FRI", zone = "Europe/Istanbul"),
            @Scheduled(cron = "0 8 11-17 * * MON-FRI", zone = "Europe/Istanbul"),
            @Scheduled(cron = "0 38 11-17 * * MON-FRI", zone = "Europe/Istanbul"),
            @Scheduled(cron = "0 58 17 * * MON-FRI", zone = "Europe/Istanbul"),
            @Scheduled(cron = "0 8 18 * * MON-FRI", zone = "Europe/Istanbul")
    })
    public void run() {
        if (!profileUtils.isProdProfile()) return;
        if (!telegramConfig.isEnabled()) return;
        if (!BistTradingCalendar.isNotOffDay()) return;

        try {
            BrokerageAkdListResponseDto response = brokerageAkdListService.getAkdList(null);
            String message = builder.build(response);

            if (message != null) {
                boolean sent = telegramClient.sendHtmlMessage(message);
                if (sent) {
                    telegramClient.sendHtmlMessage("****************************************");
                    log.info("[AKD-OZET-JOB] Piyasa kurumsal özet gönderildi");
                } else {
                    log.warn("[AKD-OZET-JOB] Mesaj gönderilemedi");
                }
            } else {
                log.debug("[AKD-OZET-JOB] Mesaj oluşturulamadı (veri yok)");
            }
        } catch (Exception e) {
            log.error("[AKD-OZET-JOB] Hata: {}", e.getMessage(), e);
        }
    }
}
