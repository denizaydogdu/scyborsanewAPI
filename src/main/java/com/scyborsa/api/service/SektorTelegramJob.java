package com.scyborsa.api.service;

import com.scyborsa.api.config.TelegramConfig;
import com.scyborsa.api.dto.SectorSummaryDto;
import com.scyborsa.api.service.telegram.SektorOzetiTelegramBuilder;
import com.scyborsa.api.service.telegram.TelegramClient;
import com.scyborsa.api.utils.BistTradingCalendar;
import com.scyborsa.api.utils.ProfileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
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

    private final TelegramClient telegramClient;
    private final TelegramConfig telegramConfig;
    private final ProfileUtils profileUtils;
    private final SectorService sectorService;
    private final SektorOzetiTelegramBuilder builder;

    /**
     * Sektör özeti Telegram gönderimini tetikler.
     * Saatte bir çalışır (10:33, 11:33... 18:33).
     */
    @Scheduled(cron = "0 33 10-18 * * MON-FRI", zone = "Europe/Istanbul")
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
