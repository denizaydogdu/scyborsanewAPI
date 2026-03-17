package com.scyborsa.api.service.job;

import com.scyborsa.api.config.TelegramConfig;
import com.scyborsa.api.dto.sector.SectorSummaryDto;
import com.scyborsa.api.service.telegram.SektorOzetiTelegramBuilder;
import com.scyborsa.api.service.telegram.TelegramClient;
import com.scyborsa.api.service.SectorService;
import com.scyborsa.api.utils.BistTradingCalendar;
import com.scyborsa.api.utils.ProfileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sektör Özeti Telegram gönderim job'u.
 *
 * <p>Saatte bir en çok yükselen ve düşen sektörleri
 * Telegram'a gönderir. SectorService cache'inden veri alır.</p>
 *
 * @see SektorOzetiTelegramBuilder
 * @see SectorService
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SektorTelegramJob {

    /** Telegram mesaj gonderim istemcisi. */
    private final TelegramClient telegramClient;

    /** Telegram yapilandirma ayarlari. */
    private final TelegramConfig telegramConfig;

    /** Spring profil kontrol yardimcisi. */
    private final ProfileUtils profileUtils;

    /** Sektor veri servisi (cache reuse). */
    private final SectorService sectorService;

    /** Sektor ozeti Telegram mesaj olusturucu. */
    private final SektorOzetiTelegramBuilder builder;

    /**
     * Sektör özeti Telegram gönderimini tetikler.
     *
     * <p>SC ile birebir uyumlu 12 sabit zamanda çalışır:</p>
     * <ul>
     *   <li>Açılış sonrası: 10:33</li>
     *   <li>Sabah: 11:03, 11:33</li>
     *   <li>Gün içi saatlik: 12:03-17:03</li>
     *   <li>Kapanış öncesi: 17:33</li>
     *   <li>Kapanış sonrası: 18:03, 18:13</li>
     * </ul>
     */
    @Schedules({
            // Açılış sonrası
            @Scheduled(cron = "0 33 10 * * MON-FRI", zone = "Europe/Istanbul"),
            // Sabah yarım saatlik
            @Scheduled(cron = "0 3 11 * * MON-FRI", zone = "Europe/Istanbul"),
            @Scheduled(cron = "0 33 11 * * MON-FRI", zone = "Europe/Istanbul"),
            // Gün içi saatlik (12:03-17:03)
            @Scheduled(cron = "0 3 12-17 * * MON-FRI", zone = "Europe/Istanbul"),
            // Kapanış öncesi
            @Scheduled(cron = "0 33 17 * * MON-FRI", zone = "Europe/Istanbul"),
            // Kapanış sonrası
            @Scheduled(cron = "0 3 18 * * MON-FRI", zone = "Europe/Istanbul"),
            @Scheduled(cron = "0 13 18 * * MON-FRI", zone = "Europe/Istanbul")
    })
    public void run() {
        if (!profileUtils.isProdProfile()) return;
        if (!telegramConfig.isEnabled()) return;
        if (!BistTradingCalendar.isNotOffDay()) return;

        try {
            List<SectorSummaryDto> summaries = sectorService.getSectorSummaries();
            String message = builder.build(summaries);

            if (message != null) {
                boolean sent = telegramClient.sendHtmlMessage(message);
                if (sent) {
                    telegramClient.sendHtmlMessage("****************************************");
                    log.info("[SEKTOR-JOB] Sektör özeti gönderildi");
                } else {
                    log.warn("[SEKTOR-JOB] Mesaj gönderilemedi");
                }
            } else {
                log.debug("[SEKTOR-JOB] Mesaj oluşturulamadı (veri yok)");
            }
        } catch (Exception e) {
            log.error("[SEKTOR-JOB] Hata: {}", e.getMessage(), e);
        }
    }
}
