package com.scyborsa.api.service.job;

import com.scyborsa.api.config.TelegramConfig;
import com.scyborsa.api.dto.fintables.AcigaSatisDto;
import com.scyborsa.api.service.enrichment.AcigaSatisService;
import com.scyborsa.api.service.telegram.TelegramClient;
import com.scyborsa.api.utils.BistTradingCalendar;
import com.scyborsa.api.utils.ProfileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Günlük açığa satış özeti Telegram gönderim job'u.
 *
 * <p>Her iş günü 20:30'da (AcigaSatisSyncJob 20:00'de çalışır, 30dk sonra)
 * en çok açığa satılan ilk 5 hisseyi Telegram'a gönderir.</p>
 *
 * @see AcigaSatisService
 * @see TelegramClient
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AcigaSatisTelegramJob {

    /** Gösterilecek maksimum hisse sayısı. */
    private static final int TOP_COUNT = 5;

    /** Türkçe tarih-saat formatlayıcı. */
    private static final DateTimeFormatter DATETIME_FMT =
            DateTimeFormatter.ofPattern("dd MMMM yyyy", new Locale("tr"));

    /** Telegram mesaj gönderim istemcisi. */
    private final TelegramClient telegramClient;

    /** Telegram yapılandırma ayarları. */
    private final TelegramConfig telegramConfig;

    /** Spring profil kontrol yardımcısı. */
    private final ProfileUtils profileUtils;

    /** Açığa satış servis katmanı. */
    private final AcigaSatisService acigaSatisService;

    /**
     * Günlük açığa satış özetini Telegram'a gönderir.
     *
     * <p>20:30 Pzt-Cuma (Europe/Istanbul) çalışır.
     * Guard: prod profil + Telegram enabled + iş günü.</p>
     */
    @Scheduled(cron = "0 30 20 * * MON-FRI", zone = "Europe/Istanbul")
    public void run() {
        if (!profileUtils.isProdProfile()) return;
        if (!telegramConfig.isEnabled()) return;
        if (!BistTradingCalendar.isNotOffDay()) return;

        try {
            List<AcigaSatisDto> tumListe = acigaSatisService.getGunlukAcigaSatislar();
            if (tumListe == null || tumListe.isEmpty()) {
                log.info("[ACIGA-SATIS-TG] Bugün açığa satış verisi yok, atlanıyor");
                return;
            }

            // En yüksek açığa satış lotuna göre sırala, ilk TOP_COUNT'u al
            List<AcigaSatisDto> topList = tumListe.stream()
                    .filter(dto -> dto.getAcigaSatisLotu() != null && dto.getAcigaSatisLotu() > 0)
                    .sorted(java.util.Comparator.comparingLong(AcigaSatisDto::getAcigaSatisLotu).reversed())
                    .limit(TOP_COUNT)
                    .toList();

            if (topList.isEmpty()) {
                log.info("[ACIGA-SATIS-TG] Filtrelenmiş açığa satış verisi yok, atlanıyor");
                return;
            }

            String html = buildMessage(topList);
            String chatId = telegramConfig.getBot().getChatId();
            int topicId = telegramConfig.getBot().getTopicId();

            boolean sent = telegramClient.sendHtmlMessage(html, chatId, topicId);
            if (sent) {
                log.info("[ACIGA-SATIS-TG] Açığa satış özeti gönderildi ({} hisse)", topList.size());
            } else {
                log.warn("[ACIGA-SATIS-TG] Açığa satış özeti gönderilemedi");
            }

        } catch (Exception e) {
            log.error("[ACIGA-SATIS-TG] Açığa satış özeti gönderim hatası: {}", e.getMessage(), e);
        }
    }

    /**
     * Telegram HTML mesajını oluşturur.
     *
     * @param topList en çok açığa satılan hisseler
     * @return HTML formatında mesaj
     */
    private String buildMessage(List<AcigaSatisDto> topList) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 <b>Günlük Açığa Satış Özeti</b>\n");
        sb.append("<i>").append(LocalDateTime.now().format(DATETIME_FMT)).append("</i>\n\n");

        int sira = 1;
        for (AcigaSatisDto dto : topList) {
            sb.append(sira).append(". <b>").append(escapeHtml(dto.getHisseSenediKodu())).append("</b>");

            if (dto.getAcigaSatisLotu() != null) {
                sb.append(" — ").append(formatLot(dto.getAcigaSatisLotu())).append(" lot");
            }

            if (dto.getToplamIslemHacmiLot() != null && dto.getToplamIslemHacmiLot() > 0
                    && dto.getAcigaSatisLotu() != null) {
                double oran = (double) dto.getAcigaSatisLotu() / dto.getToplamIslemHacmiLot() * 100;
                sb.append(String.format(" (%%%.1f)", oran));
            }

            sb.append("\n");
            sira++;
        }

        sb.append("\n<i>ScyBorsa Bot</i>");
        return sb.toString();
    }

    /**
     * Lot sayısını okunabilir formatta biçimlendirir.
     *
     * @param lot lot adedi
     * @return biçimlendirilmiş metin (ör: "123.456")
     */
    private String formatLot(Long lot) {
        return String.format("%,d", lot).replace(',', '.');
    }

    /**
     * HTML özel karakterlerini escape eder.
     *
     * @param text orijinal metin
     * @return escape edilmiş metin
     */
    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
