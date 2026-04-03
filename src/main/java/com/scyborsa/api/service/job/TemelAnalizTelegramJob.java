package com.scyborsa.api.service.job;

import com.scyborsa.api.config.TelegramConfig;
import com.scyborsa.api.dto.fintables.TemelAnalizSinyalDto;
import com.scyborsa.api.model.StockModel;
import com.scyborsa.api.repository.StockModelRepository;
import com.scyborsa.api.service.enrichment.TemelAnalizSinyalService;
import com.scyborsa.api.service.telegram.TelegramClient;
import com.scyborsa.api.utils.BistTradingCalendar;
import com.scyborsa.api.utils.ProfileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Haftalık temel analiz raporu Telegram gönderim job'u.
 *
 * <p>Her Cuma 18:00'da en güçlü temel analiz sinyaline sahip
 * ilk 10 hisseyi Telegram'a gönderir.</p>
 *
 * <p>Tüm aktif hisseler taranır, pozitif sinyal sayısına göre sıralanır.</p>
 *
 * @see TemelAnalizSinyalService
 * @see TelegramClient
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TemelAnalizTelegramJob {

    /** Gösterilecek maksimum hisse sayısı. */
    private static final int TOP_COUNT = 10;

    /** Taranacak maksimum hisse sayısı (performans sınırı). */
    private static final int MAX_SCAN_COUNT = 200;

    /** Türkçe tarih formatlayıcı. */
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd MMMM yyyy", new Locale("tr"));

    /** Telegram mesaj gönderim istemcisi. */
    private final TelegramClient telegramClient;

    /** Telegram yapılandırma ayarları. */
    private final TelegramConfig telegramConfig;

    /** Spring profil kontrol yardımcısı. */
    private final ProfileUtils profileUtils;

    /** Temel analiz sinyal servisi. */
    private final TemelAnalizSinyalService temelAnalizSinyalService;

    /** Hisse repository (aktif hisse listesi için). */
    private final StockModelRepository stockModelRepository;

    /**
     * Haftalık temel analiz raporunu Telegram'a gönderir.
     *
     * <p>Cuma 18:00 (Europe/Istanbul) çalışır.
     * Guard: prod profil + Telegram enabled. isTradingDay kontrolü YOK — Cuma akşamı.</p>
     */
    @Scheduled(cron = "0 0 18 * * FRI", zone = "Europe/Istanbul")
    public void run() {
        if (!profileUtils.isProdProfile()) return;
        if (!telegramConfig.isEnabled()) return;
        if (!BistTradingCalendar.isNotOffDay()) return;

        try {
            log.info("[TEMEL-ANALIZ-TG] Haftalık temel analiz raporu hazırlanıyor...");

            // Aktif hisseleri al (performans için sınırlı)
            List<StockModel> aktifHisseler = stockModelRepository.findActiveStocks();
            if (aktifHisseler == null || aktifHisseler.isEmpty()) {
                log.warn("[TEMEL-ANALIZ-TG] Aktif hisse yok, atlanıyor");
                return;
            }

            // Performans sınırı
            if (aktifHisseler.size() > MAX_SCAN_COUNT) {
                log.info("[TEMEL-ANALIZ-TG] {} hisse tarandı / {} toplam (ilk {} ile sınırlı)",
                        MAX_SCAN_COUNT, aktifHisseler.size(), MAX_SCAN_COUNT);
            }
            List<StockModel> taranacak = aktifHisseler.stream()
                    .limit(MAX_SCAN_COUNT)
                    .toList();

            // Her hisse için pozitif sinyal sayısını hesapla
            Map<String, List<TemelAnalizSinyalDto>> hisseSinyalleri = new LinkedHashMap<>();
            for (StockModel stock : taranacak) {
                try {
                    List<TemelAnalizSinyalDto> sinyaller = temelAnalizSinyalService
                            .getSinyaller(stock.getStockName());

                    List<TemelAnalizSinyalDto> pozitifler = sinyaller.stream()
                            .filter(s -> "POZITIF".equals(s.getSinyalYonu()))
                            .toList();

                    if (!pozitifler.isEmpty()) {
                        hisseSinyalleri.put(stock.getStockName(), pozitifler);
                    }
                } catch (Exception e) {
                    // Tek hisse hatası diğerlerini etkilemesin
                    log.debug("[TEMEL-ANALIZ-TG] {} sinyal hatası: {}", stock.getStockName(), e.getMessage());
                }
            }

            if (hisseSinyalleri.isEmpty()) {
                log.info("[TEMEL-ANALIZ-TG] Pozitif sinyalli hisse bulunamadı, atlanıyor");
                return;
            }

            // Sinyal sayısına göre sırala, ilk TOP_COUNT'u al
            List<Map.Entry<String, List<TemelAnalizSinyalDto>>> topEntries = hisseSinyalleri.entrySet()
                    .stream()
                    .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                    .limit(TOP_COUNT)
                    .toList();

            String html = buildMessage(topEntries);
            String chatId = telegramConfig.getBot().getChatId();
            int topicId = telegramConfig.getBot().getTopicId();

            boolean sent = telegramClient.sendHtmlMessage(html, chatId, topicId);
            if (sent) {
                log.info("[TEMEL-ANALIZ-TG] Temel analiz raporu gönderildi ({} hisse)", topEntries.size());
            } else {
                log.warn("[TEMEL-ANALIZ-TG] Temel analiz raporu gönderilemedi");
            }

        } catch (Exception e) {
            log.error("[TEMEL-ANALIZ-TG] Temel analiz raporu gönderim hatası: {}", e.getMessage(), e);
        }
    }

    /**
     * Telegram HTML mesajını oluşturur.
     *
     * @param topEntries en güçlü sinyalli hisseler (sıralı)
     * @return HTML formatında mesaj
     */
    private String buildMessage(List<Map.Entry<String, List<TemelAnalizSinyalDto>>> topEntries) {
        StringBuilder sb = new StringBuilder();
        sb.append("📈 <b>Haftalık Temel Analiz Raporu</b>\n");
        sb.append("<i>").append(LocalDateTime.now().format(DATE_FMT)).append("</i>\n\n");

        for (Map.Entry<String, List<TemelAnalizSinyalDto>> entry : topEntries) {
            String stockCode = entry.getKey();
            List<TemelAnalizSinyalDto> sinyaller = entry.getValue();

            sb.append("🟢 <b>").append(escapeHtml(stockCode)).append("</b> — ");

            // Sinyal açıklamalarını birleştir
            String aciklamalar = sinyaller.stream()
                    .map(s -> formatSinyalKisa(s.getSinyalTipi()))
                    .collect(Collectors.joining(" + "));
            sb.append(escapeHtml(aciklamalar));

            sb.append("\n");
        }

        sb.append("\n<i>").append(topEntries.size()).append(" hisse, pozitif sinyal sayısına göre sıralı</i>");
        sb.append("\n<i>ScyBorsa Bot</i>");
        return sb.toString();
    }

    /**
     * Sinyal tipini kısa okunabilir Türkçe formata çevirir.
     *
     * @param sinyalTipi sinyal tipi kodu
     * @return kısa açıklama
     */
    private String formatSinyalKisa(String sinyalTipi) {
        if (sinyalTipi == null) return "Sinyal";
        return switch (sinyalTipi) {
            case "DUSUK_FK" -> "Düşük F/K";
            case "GUCLU_ROE" -> "Güçlü ROE";
            case "BORC_AZALTMA" -> "Borç Azaltma";
            case "DEGER_FIRSATI" -> "Değer Fırsatı";
            case "GUCLU_TEMEL" -> "Güçlü Temel";
            default -> sinyalTipi.replace("_", " ");
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
