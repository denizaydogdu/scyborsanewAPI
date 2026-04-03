package com.scyborsa.api.service.job;

import com.scyborsa.api.config.TelegramConfig;
import com.scyborsa.api.dto.fintables.GuidanceDto;
import com.scyborsa.api.dto.fintables.HalkaArzDto;
import com.scyborsa.api.service.enrichment.GuidanceService;
import com.scyborsa.api.service.enrichment.HalkaArzService;
import com.scyborsa.api.service.telegram.TelegramClient;
import com.scyborsa.api.utils.BistTradingCalendar;
import com.scyborsa.api.utils.ProfileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Halka arz ve guidance (şirket beklentileri) Telegram gönderim job'u.
 *
 * <p>Haftalık Pazartesi 10:00'da aktif halka arzları ve güncel
 * guidance bilgilerini Telegram'a gönderir.</p>
 *
 * @see HalkaArzService
 * @see GuidanceService
 * @see TelegramClient
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HalkaArzGuidanceTelegramJob {

    /** Istanbul saat dilimi. */
    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");

    /** Türkçe tarih formatlayıcı. */
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd MMMM yyyy", new Locale("tr"));

    /** Telegram mesaj gönderim istemcisi. */
    private final TelegramClient telegramClient;

    /** Telegram yapılandırma ayarları. */
    private final TelegramConfig telegramConfig;

    /** Spring profil kontrol yardımcısı. */
    private final ProfileUtils profileUtils;

    /** Halka arz servis katmanı. */
    private final HalkaArzService halkaArzService;

    /** Guidance servis katmanı. */
    private final GuidanceService guidanceService;

    /**
     * Halka arz ve guidance bilgilerini Telegram'a gönderir.
     *
     * <p>Pazartesi 10:00 (Europe/Istanbul) çalışır.
     * Guard: prod profil + Telegram enabled. isTradingDay kontrolü YOK — Pazartesi.</p>
     */
    @Scheduled(cron = "0 0 10 * * MON", zone = "Europe/Istanbul")
    public void run() {
        if (!profileUtils.isProdProfile()) return;
        if (!telegramConfig.isEnabled()) return;
        if (!BistTradingCalendar.isNotOffDay()) return;

        String chatId = telegramConfig.getBot().getChatId();
        int topicId = telegramConfig.getBot().getTopicId();

        // 1. Aktif halka arzları gönder
        sendHalkaArzMesaji(chatId, topicId);

        // 2. Güncel guidance bilgilerini gönder
        sendGuidanceMesaji(chatId, topicId);
    }

    /**
     * Aktif halka arz mesajını oluşturup gönderir.
     *
     * @param chatId  Telegram chat ID
     * @param topicId Telegram topic ID
     */
    private void sendHalkaArzMesaji(String chatId, int topicId) {
        try {
            List<HalkaArzDto> aktifler = halkaArzService.getAktifHalkaArzlar();
            if (aktifler == null || aktifler.isEmpty()) {
                log.info("[HALKA-ARZ-TG] Aktif halka arz yok, atlanıyor");
                return;
            }

            String html = buildHalkaArzMessage(aktifler);
            boolean sent = telegramClient.sendHtmlMessage(html, chatId, topicId);
            if (sent) {
                log.info("[HALKA-ARZ-TG] Halka arz takvimi gönderildi ({} adet)", aktifler.size());
            } else {
                log.warn("[HALKA-ARZ-TG] Halka arz takvimi gönderilemedi");
            }

        } catch (Exception e) {
            log.error("[HALKA-ARZ-TG] Halka arz gönderim hatası: {}", e.getMessage(), e);
        }
    }

    /**
     * Guidance mesajını oluşturup gönderir.
     *
     * @param chatId  Telegram chat ID
     * @param topicId Telegram topic ID
     */
    private void sendGuidanceMesaji(String chatId, int topicId) {
        try {
            int currentYear = LocalDate.now(ISTANBUL_ZONE).getYear();
            List<GuidanceDto> guidancelar = guidanceService.getYilGuidance(currentYear);
            if (guidancelar == null || guidancelar.isEmpty()) {
                log.info("[GUIDANCE-TG] {} yılı için guidance yok, atlanıyor", currentYear);
                return;
            }

            String html = buildGuidanceMessage(guidancelar, currentYear);
            boolean sent = telegramClient.sendHtmlMessage(html, chatId, topicId);
            if (sent) {
                log.info("[GUIDANCE-TG] Guidance bilgileri gönderildi ({} adet)", guidancelar.size());
            } else {
                log.warn("[GUIDANCE-TG] Guidance bilgileri gönderilemedi");
            }

        } catch (Exception e) {
            log.error("[GUIDANCE-TG] Guidance gönderim hatası: {}", e.getMessage(), e);
        }
    }

    /**
     * Halka arz takvimi HTML mesajını oluşturur.
     *
     * @param aktifler aktif halka arz listesi
     * @return HTML formatında mesaj
     */
    private String buildHalkaArzMessage(List<HalkaArzDto> aktifler) {
        StringBuilder sb = new StringBuilder();
        sb.append("🏢 <b>Halka Arz Takvimi</b>\n");
        sb.append("<i>").append(LocalDateTime.now().format(DATE_FMT)).append("</i>\n\n");

        for (HalkaArzDto dto : aktifler) {
            sb.append("• <b>").append(escapeHtml(dto.getHisseSenediKodu())).append("</b>");

            if (dto.getBaslik() != null) {
                sb.append(" — ").append(escapeHtml(dto.getBaslik()));
            }

            sb.append("\n");

            if (dto.getHalkaArzFiyati() != null) {
                sb.append("  Fiyat: ").append(String.format("%.2f ₺", dto.getHalkaArzFiyati()));
            }

            if (dto.getTalepToplamaBaslangicTarihi() != null) {
                sb.append(" | Talep: ").append(escapeHtml(dto.getTalepToplamaBaslangicTarihi()));
                if (dto.getTalepToplamaBitisTarihi() != null) {
                    sb.append(" → ").append(escapeHtml(dto.getTalepToplamaBitisTarihi()));
                }
            }

            if (dto.getAraciKurum() != null) {
                sb.append("\n  Aracı Kurum: ").append(escapeHtml(dto.getAraciKurum()));
            }

            sb.append("\n\n");
        }

        sb.append("<i>ScyBorsa Bot</i>");
        return sb.toString();
    }

    /**
     * Guidance HTML mesajını oluşturur.
     *
     * @param guidancelar guidance listesi
     * @param yil         guidance yılı
     * @return HTML formatında mesaj
     */
    private String buildGuidanceMessage(List<GuidanceDto> guidancelar, int yil) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 <b>Şirket Beklentileri (").append(yil).append(")</b>\n\n");

        for (GuidanceDto dto : guidancelar) {
            sb.append("• <b>").append(escapeHtml(dto.getHisseSenediKodu())).append("</b>");

            if (dto.getBeklentiler() != null) {
                String beklenti = dto.getBeklentiler();
                // Çok uzun beklentileri kısalt
                if (beklenti.length() > 200) {
                    beklenti = beklenti.substring(0, 197) + "...";
                }
                sb.append("\n  ").append(escapeHtml(beklenti));
            }

            sb.append("\n\n");
        }

        sb.append("<i>ScyBorsa Bot</i>");
        return sb.toString();
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
