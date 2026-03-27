package com.scyborsa.api.service.job;

import com.scyborsa.api.config.TelegramConfig;
import com.scyborsa.api.dto.sector.SectorSummaryDto;
import com.scyborsa.api.service.telegram.SektorOzetiTelegramBuilder;
import com.scyborsa.api.service.telegram.TelegramClient;
import com.scyborsa.api.service.telegram.infographic.SectorSummaryCardData;
import com.scyborsa.api.service.telegram.infographic.SectorSummaryCardRenderer;
import com.scyborsa.api.service.SectorService;
import com.scyborsa.api.utils.BistTradingCalendar;
import com.scyborsa.api.utils.ProfileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

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

    /** Her kategori için gösterilecek maksimum sektör sayısı. */
    private static final int TOP_COUNT = 5;

    /** Türkçe tarih-saat formatlayıcı. */
    private static final DateTimeFormatter DATETIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm - dd MMMM yyyy", new Locale("tr"));

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

    /** Sektör özeti infografik kartı renderer (graceful degradation). */
    @Autowired(required = false)
    private SectorSummaryCardRenderer cardRenderer;

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
            boolean sent = false;

            // İnfografik kart denemesi (birincil yol)
            if (cardRenderer != null && telegramConfig.getInfographic().isEnabled()) {
                try {
                    SectorSummaryCardData cardData = buildCardData(summaries);
                    if (cardData != null) {
                        byte[] png = cardRenderer.renderCard(cardData);
                        if (png != null) {
                            sent = telegramClient.sendPhoto(png, "\uD83D\uDCCA <b>BIST Sekt\u00f6r \u00d6zeti</b> | ScyBorsa Bot");
                            if (sent) {
                                log.info("[SEKTOR-JOB] İnfografik kart gönderildi ({} KB)",
                                        png.length / 1024);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("[SEKTOR-JOB] İnfografik kart oluşturulamadı, text fallback: {}",
                            e.getMessage());
                }
            }

            // Fallback: text mesaj
            if (!sent) {
                String message = builder.build(summaries);
                if (message != null) {
                    sent = telegramClient.sendHtmlMessage(message);
                }
            }

            if (sent) {
                long rateLimitMs = telegramConfig.getSendRateLimitMs();
                if (rateLimitMs > 0) Thread.sleep(rateLimitMs);
                telegramClient.sendHtmlMessage("****************************************");
                log.info("[SEKTOR-JOB] Sektör özeti gönderildi");
            } else {
                log.debug("[SEKTOR-JOB] Mesaj oluşturulamadı (veri yok)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[SEKTOR-JOB] İş parçacığı kesildi");
        } catch (Exception e) {
            log.error("[SEKTOR-JOB] Hata: {}", e.getMessage(), e);
        }
    }

    /**
     * Sektör özet listesinden infografik kart verisi oluşturur.
     *
     * <p>Aynı filtreleme mantığını kullanır: pozitif değişimli ilk 5 sektör
     * yükselen, negatif değişimli ilk 5 sektör düşen olarak ayrılır.</p>
     *
     * @param summaries sektör özet listesi (avgChangePercent desc sıralı)
     * @return kart verisi, veri yoksa {@code null}
     */
    private SectorSummaryCardData buildCardData(List<SectorSummaryDto> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return null;
        }

        List<SectorSummaryCardData.SectorItem> rising = summaries.stream()
                .filter(s -> s.getAvgChangePercent() > 0)
                .limit(TOP_COUNT)
                .map(s -> SectorSummaryCardData.SectorItem.builder()
                        .name(s.getDisplayName())
                        .changePercent(s.getAvgChangePercent())
                        .stockCount(s.getStockCount())
                        .build())
                .toList();

        List<SectorSummaryCardData.SectorItem> falling = summaries.stream()
                .filter(s -> s.getAvgChangePercent() < 0)
                .sorted((a, b) -> Double.compare(a.getAvgChangePercent(), b.getAvgChangePercent()))
                .limit(TOP_COUNT)
                .map(s -> SectorSummaryCardData.SectorItem.builder()
                        .name(s.getDisplayName())
                        .changePercent(s.getAvgChangePercent())
                        .stockCount(s.getStockCount())
                        .build())
                .toList();

        if (rising.isEmpty() && falling.isEmpty()) {
            return null;
        }

        return SectorSummaryCardData.builder()
                .timestamp(LocalDateTime.now().format(DATETIME_FMT))
                .risingSectors(rising)
                .fallingSectors(falling)
                .build();
    }
}
