package com.scyborsa.api.service.job;

import com.scyborsa.api.config.TelegramConfig;
import com.scyborsa.api.dto.kap.KapNewsItemDto;
import com.scyborsa.api.dto.kap.KapNewsResponseDto;
import com.scyborsa.api.service.kap.HaberDetailFetcher;
import com.scyborsa.api.service.kap.KapNewsClient;
import com.scyborsa.api.service.telegram.KapHaberTelegramBuilder;
import com.scyborsa.api.service.telegram.TelegramClient;
import com.scyborsa.api.utils.ProfileUtils;
import com.scyborsa.api.repository.HaberDetayRepository;
import com.scyborsa.api.model.haber.HaberDetay;
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

    /** Telegram mesaj gonderim istemcisi. */
    private final TelegramClient telegramClient;

    /** Telegram yapilandirma ayarlari. */
    private final TelegramConfig telegramConfig;

    /** Spring profil kontrol yardimcisi. */
    private final ProfileUtils profileUtils;

    /** TradingView KAP haber istemcisi (news-mediator API). */
    private final KapNewsClient kapNewsClient;

    /** KAP haber Telegram mesaj olusturucu. */
    private final KapHaberTelegramBuilder builder;

    /** Haber detay repository. */
    private final HaberDetayRepository haberDetayRepository;

    /** Haber detay fetcher (on-demand scraping). */
    private final HaberDetailFetcher haberDetailFetcher;

    /** Gonderilmis haber ID'leri (gunluk reset). */
    private final Set<String> sentNewsIds = ConcurrentHashMap.newKeySet();

    /** Uygulama baslangic zamani (epoch seconds) — restart flood korumasi icin. */
    private final long startupEpochSecond = Instant.now().getEpochSecond();

    /**
     * KAP haber Telegram gonderimini tetikler.
     * Her 10 dakikada bir calisir, 7/24 (hafta sonu dahil).
     */
    @Scheduled(cron = "0 0/10 * * * *", zone = "Europe/Istanbul")
    public void run() {
        log.info("[KAP-TELEGRAM-JOB] Cron tetiklendi");
        if (!profileUtils.isProdProfile()) return;
        if (!telegramConfig.isEnabled()) return;

        try {
            KapNewsResponseDto response = kapNewsClient.fetchKapNews();
            List<KapNewsItemDto> items = (response != null) ? response.getItems() : null;
            if (items == null || items.isEmpty()) {
                log.debug("[KAP-TELEGRAM-JOB] Haber yok");
                return;
            }
            log.info("[KAP-TELEGRAM-JOB] {} KAP haberi alindi", items.size());

            int maxPerCycle = telegramConfig.getKap().getMaxPerCycle();

            int sentCount = 0;
            for (KapNewsItemDto item : items) {
                if (item.getId() == null) continue;

                // Restart flood koruması: uygulama başlamadan önceki haberleri atla
                if (item.getPublished() != null && item.getPublished() < startupEpochSecond) {
                    sentNewsIds.add(item.getId());
                    continue;
                }

                // Atomic dedup: add() false dönerse zaten gönderilmiş
                if (!sentNewsIds.add(item.getId())) {
                    continue;
                }

                String detailContent = lookupDetailContent(item.getId());
                String message = builder.build(item, detailContent);
                if (message == null) {
                    continue;
                }

                int kapTopicId = telegramConfig.getKap().getTopicId();
                boolean sent = (kapTopicId > 0)
                        ? telegramClient.sendHtmlMessageToTopic(message, kapTopicId)
                        : telegramClient.sendHtmlMessage(message);
                if (!sent) {
                    sentNewsIds.remove(item.getId());
                } else {
                    if (kapTopicId > 0) {
                        telegramClient.sendHtmlMessageToTopic("****************************************", kapTopicId);
                    } else {
                        telegramClient.sendHtmlMessage("****************************************");
                    }
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
     * HaberDetay tablosundan detay icerigini arar.
     * Detay henuz cekilmemisse on-demand fetch yapar.
     *
     * @param newsId haber kimligi
     * @return detay icerigi veya null
     */
    private String lookupDetailContent(String newsId) {
        if (newsId == null) return null;
        try {
            var optHaber = haberDetayRepository.findByNewsId(newsId);
            if (optHaber.isEmpty()) return null;

            HaberDetay haber = optHaber.get();

            // Detay henüz çekilmemişse on-demand fetch
            if (!haber.isFetched()) {
                haberDetailFetcher.fetchDetailOnDemand(haber);
                // Refresh from DB
                haber = haberDetayRepository.findByNewsId(newsId).orElse(haber);
            }

            // detailContent varsa onu döndür, yoksa shortDescription
            if (haber.getDetailContent() != null && !haber.getDetailContent().isBlank()) {
                return haber.getDetailContent();
            }
            return haber.getShortDescription();
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
