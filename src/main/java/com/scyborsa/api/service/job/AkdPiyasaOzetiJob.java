package com.scyborsa.api.service.job;

import com.scyborsa.api.config.TelegramConfig;
import com.scyborsa.api.dto.enrichment.BrokerageAkdListResponseDto;
import com.scyborsa.api.dto.enrichment.BrokerageAkdListResponseDto.BrokerageAkdItemDto;
import com.scyborsa.api.service.enrichment.BrokerageAkdListService;
import com.scyborsa.api.service.telegram.AkdPiyasaOzetiTelegramBuilder;
import com.scyborsa.api.service.telegram.TelegramClient;
import com.scyborsa.api.service.telegram.TelegramVolumeFormatter;
import com.scyborsa.api.service.telegram.infographic.AkdSummaryCardData;
import com.scyborsa.api.service.telegram.infographic.AkdSummaryCardRenderer;
import com.scyborsa.api.utils.BistTradingCalendar;
import com.scyborsa.api.utils.ProfileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * AKD Piyasa Özeti Telegram gönderim job'u.
 *
 * <p>SC ile birebir uyumlu 18 sabit zamanda top 5 alıcı ve top 5 satıcı
 * kurumları Telegram'a gönderir. İnfografik kart birincil yol,
 * text mesaj fallback olarak kullanılır.</p>
 *
 * @see AkdPiyasaOzetiTelegramBuilder
 * @see AkdSummaryCardRenderer
 * @see BrokerageAkdListService
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AkdPiyasaOzetiJob {

    /** Gösterilecek maksimum kurum sayısı (alıcı ve satıcı için ayrı). */
    private static final int TOP_COUNT = 5;

    /** Saat formatlayıcı (HH:mm). */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

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

    /** AKD piyasa özeti infografik kartı renderer (graceful degradation). */
    @Autowired(required = false)
    private AkdSummaryCardRenderer cardRenderer;

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
            boolean sent = false;

            // İnfografik kart denemesi (birincil yol)
            if (cardRenderer != null && telegramConfig.getInfographic().isEnabled()) {
                try {
                    AkdSummaryCardData cardData = buildCardData(response);
                    if (cardData != null) {
                        byte[] png = cardRenderer.renderCard(cardData);
                        if (png != null) {
                            sent = telegramClient.sendPhoto(png, "");
                            if (sent) {
                                log.info("[AKD-OZET-JOB] İnfografik kart gönderildi ({} KB)",
                                        png.length / 1024);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("[AKD-OZET-JOB] İnfografik kart oluşturulamadı, text fallback: {}",
                            e.getMessage());
                }
            }

            // Fallback: text mesaj
            if (!sent) {
                String message = builder.build(response);
                if (message != null) {
                    sent = telegramClient.sendHtmlMessage(message);
                }
            }

            if (sent) {
                long rateLimitMs = telegramConfig.getSendRateLimitMs();
                if (rateLimitMs > 0) Thread.sleep(rateLimitMs);
                telegramClient.sendHtmlMessage("****************************************");
                log.info("[AKD-OZET-JOB] Piyasa kurumsal özet gönderildi");
            } else {
                log.debug("[AKD-OZET-JOB] Mesaj oluşturulamadı (veri yok)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[AKD-OZET-JOB] İş parçacığı kesildi");
        } catch (Exception e) {
            log.error("[AKD-OZET-JOB] Hata: {}", e.getMessage(), e);
        }
    }

    /**
     * AKD listesinden infografik kart verisi oluşturur.
     *
     * <p>Aynı filtreleme mantığını kullanır: netVolume > 0 alıcı,
     * netVolume < 0 satıcı olarak ayrılır, her biri en fazla 5 kurum.</p>
     *
     * @param response AKD listesi response DTO'su
     * @return kart verisi, veri yoksa {@code null}
     */
    private AkdSummaryCardData buildCardData(BrokerageAkdListResponseDto response) {
        if (response == null || response.getItems() == null || response.getItems().isEmpty()) {
            return null;
        }

        List<BrokerageAkdItemDto> items = response.getItems();

        List<AkdSummaryCardData.BrokerageItem> topBuyers = items.stream()
                .filter(i -> i.getNetVolume() > 0)
                .sorted(Comparator.comparingLong(BrokerageAkdItemDto::getNetVolume).reversed())
                .limit(TOP_COUNT)
                .map(i -> AkdSummaryCardData.BrokerageItem.builder()
                        .name(i.getShortTitle())
                        .volume(TelegramVolumeFormatter.formatVolumeTurkish(i.getNetVolume()))
                        .build())
                .toList();

        List<AkdSummaryCardData.BrokerageItem> topSellers = items.stream()
                .filter(i -> i.getNetVolume() < 0)
                .sorted(Comparator.comparingLong(BrokerageAkdItemDto::getNetVolume))
                .limit(TOP_COUNT)
                .map(i -> AkdSummaryCardData.BrokerageItem.builder()
                        .name(i.getShortTitle())
                        .volume(TelegramVolumeFormatter.formatVolumeTurkish(Math.abs(i.getNetVolume())))
                        .build())
                .toList();

        if (topBuyers.isEmpty() && topSellers.isEmpty()) {
            return null;
        }

        long buyerCount = items.stream().filter(i -> i.getNetVolume() > 0).count();
        long sellerCount = items.stream().filter(i -> i.getNetVolume() < 0).count();

        return AkdSummaryCardData.builder()
                .timestamp(LocalTime.now().format(TIME_FMT))
                .topBuyers(topBuyers)
                .topSellers(topSellers)
                .buyerCount(buyerCount)
                .sellerCount(sellerCount)
                .build();
    }
}
