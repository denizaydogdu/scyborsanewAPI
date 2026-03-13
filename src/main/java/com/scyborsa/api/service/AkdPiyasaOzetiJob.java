package com.scyborsa.api.service;

import com.scyborsa.api.config.TelegramConfig;
import com.scyborsa.api.dto.enrichment.BrokerageAkdListResponseDto;
import com.scyborsa.api.service.telegram.AkdPiyasaOzetiTelegramBuilder;
import com.scyborsa.api.service.telegram.TelegramClient;
import com.scyborsa.api.utils.BistTradingCalendar;
import com.scyborsa.api.utils.ProfileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AKD Piyasa Özeti Telegram gönderim job'u.
 *
 * <p>Her 30 dakikada bir top 5 alıcı ve top 5 satıcı kurumları
 * Telegram'a gönderir. Seans saatlerinde çalışır (10:03-17:33).</p>
 *
 * @see AkdPiyasaOzetiTelegramBuilder
 * @see BrokerageAkdListService
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AkdPiyasaOzetiJob {

    private final TelegramClient telegramClient;
    private final TelegramConfig telegramConfig;
    private final ProfileUtils profileUtils;
    private final BrokerageAkdListService brokerageAkdListService;
    private final AkdPiyasaOzetiTelegramBuilder builder;

    /**
     * AKD piyasa özeti Telegram gönderimini tetikler.
     * Her 30 dakikada bir çalışır (10:03, 10:33, 11:03... 17:33).
     */
    @Scheduled(cron = "0 3/30 10-17 * * MON-FRI", zone = "Europe/Istanbul")
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
