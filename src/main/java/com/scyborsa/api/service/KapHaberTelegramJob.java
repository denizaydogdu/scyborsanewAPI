package com.scyborsa.api.service;

import com.scyborsa.api.config.TelegramConfig;
import com.scyborsa.api.dto.kap.KapNewsItemDto;
import com.scyborsa.api.service.kap.FintablesNewsService;
import com.scyborsa.api.service.telegram.KapHaberTelegramBuilder;
import com.scyborsa.api.service.telegram.TelegramClient;
import com.scyborsa.api.utils.ProfileUtils;
import com.scyborsa.api.repository.HaberDetayRepository;
import com.scyborsa.api.model.HaberDetay;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KAP Haber Telegram gonderim job'u.
 *
 * <p>Her 10 dakikada yeni KAP haberlerini Telegram'a gonderir.
 * In-memory dedup ile ayni haberin tekrar gonderilmesini engeller.
 * Hafta sonu dahil 7/24 calisir.</p>
 *
 * @see KapHaberTelegramBuilder
 * @see FintablesNewsService
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KapHaberTelegramJob {

    private final TelegramClient telegramClient;
    private final TelegramConfig telegramConfig;
    private final ProfileUtils profileUtils;
    private final FintablesNewsService fintablesNewsService;
    private final KapHaberTelegramBuilder builder;
    private final HaberDetayRepository haberDetayRepository;

    /** Gonderilmis haber ID'leri (gunluk reset). */
    private final Set<String> sentNewsIds = ConcurrentHashMap.newKeySet();

    /**
     * KAP haber Telegram gonderimini tetikler.
     * Her 10 dakikada bir calisir, 7/24 (hafta sonu dahil).
     */
    @Scheduled(cron = "0 0/10 * * * *", zone = "Europe/Istanbul")
    public void run() {
        if (!profileUtils.isProdProfile()) return;
        if (!telegramConfig.isEnabled()) return;

        try {
            List<KapNewsItemDto> items = fintablesNewsService.getKapNewsItems();
            if (items == null || items.isEmpty()) {
                log.debug("[KAP-TELEGRAM-JOB] Haber yok");
                return;
            }

            long fifteenMinAgo = Instant.now().minusSeconds(900).getEpochSecond();
            int maxPerCycle = telegramConfig.getKap().getMaxPerCycle();

            int sentCount = 0;
            for (KapNewsItemDto item : items) {
                if (item.getId() == null) continue;

                // Restart flood koruması: sadece son 15 dakikadaki haberleri gönder
                if (item.getPublished() != null && item.getPublished() < fifteenMinAgo) {
                    sentNewsIds.add(item.getId());
                    continue;
                }

                // Atomic dedup: add() false dönerse zaten gönderilmiş
                if (!sentNewsIds.add(item.getId())) {
                    continue;
                }

                String shortDesc = lookupShortDescription(item.getId());
                String message = builder.build(item, shortDesc);
                if (message == null) {
                    continue;
                }

                int kapTopicId = telegramConfig.getKap().getTopicId();
                boolean sent = (kapTopicId > 0)
                        ? telegramClient.sendHtmlMessageToTopic(message, kapTopicId)
                        : telegramClient.sendHtmlMessage(message);
                if (!sent) {
                    sentNewsIds.remove(item.getId());
                }

                sentCount += sent ? 1 : 0;
                if (sentCount >= maxPerCycle) {
                    log.info("[KAP-TELEGRAM-JOB] Çalışma başına limit aşıldı: {}", maxPerCycle);
                    break;
                }
                Thread.sleep(telegramConfig.getSendRateLimitMs());
            }

            if (sentCount > 0) {
                log.info("[KAP-TELEGRAM-JOB] {} yeni haber gonderildi", sentCount);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[KAP-TELEGRAM-JOB] Is parcacigi kesildi");
        } catch (Exception e) {
            log.error("[KAP-TELEGRAM-JOB] Hata: {}", e.getMessage(), e);
        }
    }

    /**
     * HaberDetay tablosundan kisa aciklama arar.
     *
     * @param newsId haber kimligi
     * @return kisa aciklama veya null
     */
    private String lookupShortDescription(String newsId) {
        if (newsId == null) return null;
        try {
            return haberDetayRepository.findByNewsId(newsId)
                    .map(HaberDetay::getShortDescription)
                    .orElse(null);
        } catch (Exception e) {
            log.debug("[KAP-TELEGRAM-JOB] HaberDetay lookup hatasi: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Gunluk dedup set'ini temizler.
     * Her gun gece yarisi calisir.
     */
    @Scheduled(cron = "0 0 0 * * *", zone = "Europe/Istanbul")
    public void resetDailyState() {
        int size = sentNewsIds.size();
        sentNewsIds.clear();
        log.info("[KAP-TELEGRAM-JOB] Gunluk reset: {} haber ID temizlendi", size);
    }
}
