package com.scyborsa.api.service.job;

import com.scyborsa.api.config.TelegramConfig;
import com.scyborsa.api.dto.fintables.VbtsTedbirDto;
import com.scyborsa.api.service.enrichment.VbtsTedbirService;
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
 * VBTS tedbirli hisse Telegram gönderim job'u.
 *
 * <p>Her iş günü 10:00'da (VbtsTedbirSyncJob 09:30'da çalışır) aktif
 * tedbirli hisseleri Telegram'a gönderir.</p>
 *
 * @see VbtsTedbirService
 * @see TelegramClient
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VbtsTedbirTelegramJob {

    /** Türkçe tarih formatlayıcı. */
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd MMMM yyyy", new Locale("tr"));

    /** Telegram mesaj gönderim istemcisi. */
    private final TelegramClient telegramClient;

    /** Telegram yapılandırma ayarları. */
    private final TelegramConfig telegramConfig;

    /** Spring profil kontrol yardımcısı. */
    private final ProfileUtils profileUtils;

    /** VBTS tedbir servis katmanı. */
    private final VbtsTedbirService vbtsTedbirService;

    /**
     * Aktif VBTS tedbirlerini Telegram'a gönderir.
     *
     * <p>10:00 Pzt-Cuma (Europe/Istanbul) çalışır.
     * Guard: prod profil + Telegram enabled + iş günü.</p>
     */
    @Scheduled(cron = "0 0 10 * * MON-FRI", zone = "Europe/Istanbul")
    public void run() {
        if (!profileUtils.isProdProfile()) return;
        if (!telegramConfig.isEnabled()) return;
        if (!BistTradingCalendar.isNotOffDay()) return;

        try {
            List<VbtsTedbirDto> tedbirler = vbtsTedbirService.getAktifTedbirler();
            if (tedbirler == null || tedbirler.isEmpty()) {
                log.info("[VBTS-TG] Aktif VBTS tedbiri yok, atlanıyor");
                return;
            }

            String html = buildMessage(tedbirler);
            String chatId = telegramConfig.getBot().getChatId();
            int topicId = telegramConfig.getBot().getTopicId();

            boolean sent = telegramClient.sendHtmlMessage(html, chatId, topicId);
            if (sent) {
                log.info("[VBTS-TG] VBTS tedbir özeti gönderildi ({} tedbir)", tedbirler.size());
            } else {
                log.warn("[VBTS-TG] VBTS tedbir özeti gönderilemedi");
            }

        } catch (Exception e) {
            log.error("[VBTS-TG] VBTS tedbir gönderim hatası: {}", e.getMessage(), e);
        }
    }

    /**
     * Telegram HTML mesajını oluşturur.
     *
     * @param tedbirler aktif tedbir listesi
     * @return HTML formatında mesaj
     */
    private String buildMessage(List<VbtsTedbirDto> tedbirler) {
        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ <b>VBTS Tedbirli Hisseler</b>\n");
        sb.append("<i>").append(LocalDateTime.now().format(DATE_FMT)).append("</i>\n\n");

        for (VbtsTedbirDto dto : tedbirler) {
            sb.append("🔴 <b>").append(escapeHtml(dto.getHisseSenediKodu())).append("</b>");
            sb.append(" — ").append(escapeHtml(formatTedbirTipi(dto.getTedbirTipi())));

            if (dto.getTedbirBaslangicTarihi() != null) {
                sb.append(" (başlangıç: ").append(escapeHtml(dto.getTedbirBaslangicTarihi())).append(")");
            }

            sb.append("\n");
        }

        sb.append("\n<i>Toplam ").append(tedbirler.size()).append(" aktif tedbir</i>");
        sb.append("\n<i>ScyBorsa Bot</i>");
        return sb.toString();
    }

    /**
     * Tedbir tipi kodunu okunabilir Türkçe formata çevirir.
     *
     * @param tedbirTipi tedbir tipi kodu
     * @return okunabilir format
     */
    private String formatTedbirTipi(String tedbirTipi) {
        if (tedbirTipi == null) return "bilinmiyor";
        return switch (tedbirTipi) {
            case "kredili_alim_aciga_satis" -> "Kredili Alım / Açığa Satış";
            case "brut_takas" -> "Brüt Takas";
            case "tek_fiyat" -> "Tek Fiyat";
            case "emir_paketi" -> "Emir Paketi";
            case "internet_emir_yasağı" -> "İnternet Emir Yasağı";
            case "emir_iptal_yasağı" -> "Emir İptal Yasağı";
            default -> tedbirTipi.replace("_", " ");
        };
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
