package com.scyborsa.api.service.telegram;

import com.scyborsa.api.config.TelegramConfig;
import com.scyborsa.api.dto.enrichment.*;
import com.scyborsa.api.model.ScreenerResultModel;
import com.scyborsa.api.service.enrichment.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Telegram mesaj formatlayicisi.
 *
 * <p>Screener tarama sonuclarini Telegram HTML formatina donusturur.
 * Opsiyonel enrichment servisleri ile zenginlestirilmis mesajlar olusturur.</p>
 *
 * <p><b>Graceful Degradation:</b> Tum enrichment servisleri
 * {@code @Autowired(required=false)} ile inject edilir. Servis yoksa
 * (null) ilgili section atlanir, mesaj yine gonderilir.</p>
 *
 * @see TelegramSendService
 * @see TelegramClient
 */
@Slf4j
@Component
public class TelegramMessageFormatter {

    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");
    private static final Locale TURKISH_LOCALE = new Locale("tr", "TR");
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm", TURKISH_LOCALE);
    private static final DateTimeFormatter DATETIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss - dd MMMM yyyy", TURKISH_LOCALE);

    // ==================== OPTIONAL ENRICHMENT SERVICES ====================

    @Autowired(required = false)
    private KurumsalGucService kurumsalGucService;

    @Autowired(required = false)
    private PerStockAKDService perStockAKDService;

    @Autowired(required = false)
    private FintablesSummaryService fintablesSummaryService;

    @Autowired(required = false)
    private FintablesFonPozisyonService fonPozisyonService;

    @Autowired(required = false)
    private FintablesAnalystService analystService;

    @Autowired(required = false)
    private TakasApiService takasApiService;

    @Autowired
    private TelegramConfig telegramConfig;

    // ==================== PUBLIC FORMATTERS ====================

    /**
     * Birden fazla taramada cikan hisse icin gruplanmis mesaj formatlar.
     *
     * @param stockName hisse kodu
     * @param results tarama sonuclari
     * @return HTML formatinda mesaj; KGS filtresi devreye girerse {@code null} (gonderme),
     *         sonuclar bossa {@code ""} (bos string)
     */
    public String formatGroupedStockMessage(String stockName, List<ScreenerResultModel> results) {
        if (results == null || results.isEmpty()) return "";

        ScreenerResultModel latest = results.get(results.size() - 1);
        Double price = latest.getPrice();
        Double changePercent = latest.getPercentage();
        String changeEmoji = (changePercent != null && changePercent >= 0) ? "🟢" : "🔴";
        String changeSign = (changePercent != null && changePercent >= 0) ? "+" : "";
        String chartUrl = telegramConfig.getChartBaseUrl() + stockName;

        // TP/SL
        Double tp = latest.getTpPrice();
        Double sl = latest.getSlPrice();

        // Ortak tarama adlari ve zaman bilgisi
        String commonNames = results.stream()
                .map(ScreenerResultModel::getScreenerName)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.joining(", "));
        LocalTime firstTime = results.stream()
                .map(ScreenerResultModel::getScreenerTime)
                .filter(java.util.Objects::nonNull)
                .min(LocalTime::compareTo)
                .orElse(null);
        LocalTime lastTime = results.stream()
                .map(ScreenerResultModel::getScreenerTime)
                .filter(java.util.Objects::nonNull)
                .max(LocalTime::compareTo)
                .orElse(null);

        // Enrichment sections
        KurumsalGucDTO kgsDTO = fetchKgsData(stockName);

        // KGS filtre kontrolu
        if (shouldFilterByKgs(kgsDTO)) {
            log.info("[FORMATTER] KGS filtresi: {} atlandi | KGS: {}", stockName,
                    kgsDTO != null ? kgsDTO.getSkor() : "N/A");
            return null; // null = gonderme
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📊 <b>%s</b> %s\n\n", escapeHtml(stockName), changeEmoji));
        sb.append(String.format("💰 <b>Fiyat:</b> <code>%.2f ₺</code>\n", price != null ? price : 0.0));
        sb.append(String.format("📊 <b>Değişim:</b> <code>%s%.2f%%</code>\n\n",
                changeSign, changePercent != null ? changePercent : 0.0));

        // Enrichment sections (her biri null-safe)
        FintablesSummaryDTO fintablesSummary = fetchFintablesSummary(stockName);
        sb.append(buildSektorSection(fintablesSummary));
        sb.append(buildGetiriAnaliziSection(fintablesSummary));
        sb.append(buildKurumsalGucSection(kgsDTO));
        sb.append(buildFonPozisyonlariSection(stockName));
        sb.append(buildModelPortfolioSection(stockName));
        sb.append(buildKurumDagilimiSection(stockName));
        sb.append(buildTakasDagilimiSection(stockName));

        // TP/SL
        if (tp != null && tp > 0) {
            double tpPct = price != null && price > 0 ? ((tp - price) / price) * 100 : 0;
            sb.append(String.format("🎯 <b>TP:</b> <code>%.2f ₺</code> (%+.1f%%)\n", tp, tpPct));
        }
        if (sl != null && sl > 0) {
            double slPct = price != null && price > 0 ? ((sl - price) / price) * 100 : 0;
            sb.append(String.format("🛑 <b>SL:</b> <code>%.2f ₺</code> (%.1f%%)\n", sl, slPct));
        }
        sb.append("\n");

        // Tarama bilgisi
        int distinctCount = (int) results.stream().map(ScreenerResultModel::getScreenerName).distinct().count();
        sb.append(String.format("📋 <b>%d ortak tarama:</b> %s\n", distinctCount, escapeHtml(commonNames)));

        if (firstTime != null && lastTime != null) {
            sb.append(String.format("⏰ İlk sinyal: %s | Son: %s\n",
                    firstTime.format(TIME_FORMATTER), lastTime.format(TIME_FORMATTER)));
        }

        sb.append(String.format("📈 <a href=\"%s\">TradingView</a>\n\n", chartUrl));
        sb.append("🤖 <i>ScyBorsa Bot</i>");

        return sb.toString();
    }

    /**
     * Tek taramada cikan hisse icin mesaj formatlar.
     *
     * @param stockName hisse kodu
     * @param result tarama sonucu
     * @return HTML formatinda mesaj; KGS filtresi devreye girerse {@code null} (gonderme),
     *         sonuc null ise {@code ""} (bos string)
     */
    public String formatSingleStockMessage(String stockName, ScreenerResultModel result) {
        if (result == null) return "";

        Double price = result.getPrice();
        Double changePercent = result.getPercentage();
        String changeEmoji = (changePercent != null && changePercent >= 0) ? "🟢" : "🔴";
        String changeSign = (changePercent != null && changePercent >= 0) ? "+" : "";
        String chartUrl = telegramConfig.getChartBaseUrl() + stockName;

        Double tp = result.getTpPrice();
        Double sl = result.getSlPrice();

        // KGS
        KurumsalGucDTO kgsDTO = fetchKgsData(stockName);
        if (shouldFilterByKgs(kgsDTO)) {
            log.info("[FORMATTER] KGS filtresi: {} atlandi", stockName);
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📊 <b>%s</b> %s\n\n", escapeHtml(stockName), changeEmoji));
        sb.append(String.format("💰 <b>Fiyat:</b> <code>%.2f ₺</code>\n", price != null ? price : 0.0));
        sb.append(String.format("📊 <b>Değişim:</b> <code>%s%.2f%%</code>\n\n",
                changeSign, changePercent != null ? changePercent : 0.0));

        FintablesSummaryDTO fintablesSummary = fetchFintablesSummary(stockName);
        sb.append(buildSektorSection(fintablesSummary));
        sb.append(buildGetiriAnaliziSection(fintablesSummary));
        sb.append(buildKurumsalGucSection(kgsDTO));
        sb.append(buildFonPozisyonlariSection(stockName));
        sb.append(buildModelPortfolioSection(stockName));
        sb.append(buildKurumDagilimiSection(stockName));
        sb.append(buildTakasDagilimiSection(stockName));

        if (tp != null && tp > 0) {
            double tpPct = price != null && price > 0 ? ((tp - price) / price) * 100 : 0;
            sb.append(String.format("🎯 <b>TP:</b> <code>%.2f ₺</code> (%+.1f%%)\n", tp, tpPct));
        }
        if (sl != null && sl > 0) {
            double slPct = price != null && price > 0 ? ((sl - price) / price) * 100 : 0;
            sb.append(String.format("🛑 <b>SL:</b> <code>%.2f ₺</code> (%.1f%%)\n", sl, slPct));
        }
        sb.append("\n");

        sb.append(String.format("📋 <b>Tarama:</b> <i>%s</i>\n", escapeHtml(result.getScreenerName())));
        if (result.getScreenerTime() != null) {
            sb.append(String.format("⏰ Sinyal: %s\n", result.getScreenerTime().format(TIME_FORMATTER)));
        }
        sb.append(String.format("📈 <a href=\"%s\">TradingView</a>\n\n", chartUrl));
        sb.append("🤖 <i>ScyBorsa Bot</i>");

        return sb.toString();
    }

    /**
     * Gunluk ozet mesaji formatlar.
     *
     * @param totalSent toplam gonderilen hisse sayisi
     * @param groupedCount gruplanmis mesaj sayisi
     * @param singleCount tekil mesaj sayisi
     * @return HTML formatinda ozet
     */
    public String formatDailySummary(int totalSent, int groupedCount, int singleCount) {
        String now = ZonedDateTime.now(ISTANBUL_ZONE).format(DATETIME_FORMATTER);
        return String.format(
                "📊 <b>Günlük Telegram Özeti</b>\n\n" +
                "📤 Toplam: %d hisse\n" +
                "📋 Gruplanmış: %d\n" +
                "📋 Tekil: %d\n" +
                "🕐 %s\n\n" +
                "🤖 <i>ScyBorsa Bot</i>",
                totalSent, groupedCount, singleCount, now);
    }

    // ==================== SECTION BUILDERS (private) ====================

    /**
     * KGS verisi fetch eder. Servis yoksa null doner.
     */
    private KurumsalGucDTO fetchKgsData(String stockName) {
        if (kurumsalGucService == null) return null;
        try {
            return kurumsalGucService.calculateScore(stockName);
        } catch (Exception e) {
            log.debug("[FORMATTER] KGS hesaplama hatasi ({}): {}", stockName, e.getMessage());
            return null;
        }
    }

    /**
     * KGS filtresi kontrolu.
     */
    private boolean shouldFilterByKgs(KurumsalGucDTO kgsDTO) {
        if (!telegramConfig.getKgs().isFilterEnabled()) return false;
        if (kgsDTO == null || kgsDTO.getSkor() == null) return false; // Hesaplanamadiysa filtre UYGULANMAZ
        return kgsDTO.getSkor() < telegramConfig.getKgs().getMinScore();
    }

    /**
     * FintablesSummary verisi fetch eder. Servis yoksa null doner.
     */
    private FintablesSummaryDTO fetchFintablesSummary(String stockName) {
        if (fintablesSummaryService == null) return null;
        try {
            return fintablesSummaryService.getStockSummary(stockName);
        } catch (Exception e) {
            log.debug("[FORMATTER] Fintables summary hatasi ({}): {}", stockName, e.getMessage());
            return null;
        }
    }

    /** Sektor bilgisi section'i. */
    private String buildSektorSection(FintablesSummaryDTO summary) {
        if (summary == null || summary.getSektorTitle() == null || summary.getSektorTitle().isBlank()) return "";
        return String.format("📂 <b>Sektör:</b> %s\n\n", escapeHtml(summary.getSektorTitle()));
    }

    /** Getiri analizi section'i. */
    private String buildGetiriAnaliziSection(FintablesSummaryDTO summary) {
        if (summary == null || summary.getYield1y() == null) return "";
        FintablesSummaryDTO.YieldData y = summary.getYield1y();
        if (y.getLow() == null || y.getHigh() == null) return "";
        return String.format("📈 <b>Yıl İçi:</b> Düşük: <code>%.2f₺</code> → Yüksek: <code>%.2f₺</code>\n\n",
                y.getLow(), y.getHigh());
    }

    /** Kurumsal Guc Skoru section'i. */
    private String buildKurumsalGucSection(KurumsalGucDTO kgsDTO) {
        if (kgsDTO == null) return "";
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("🏛️ <b>KURUMSAL GÜÇ:</b> ")
              .append(kgsDTO.getSkor() != null ? kgsDTO.getSkor() : "N/A").append("/100 ")
              .append(kgsDTO.getEmoji() != null ? kgsDTO.getEmoji() : "");

            Integer momentum = kgsDTO.getMomentumSkoru();
            if (momentum != null && momentum >= 70) {
                sb.append(" <b>[📈 MOMENTUM: Artan]</b>");
            } else if (momentum != null && momentum <= 30) {
                sb.append(" <b>[📉 MOMENTUM: Azalan]</b>");
            }
            sb.append("\n");

            sb.append("  📊 <b>Etiket:</b> <i>").append(escapeHtml(kgsDTO.getEtiket())).append("</i>\n");
            sb.append("  💰 <b>30 Gün Net:</b> <code>").append(escapeHtml(kgsDTO.getFormattedNetPozisyonTL())).append("</code>\n");
            sb.append("  📈 <b>Süreklilik:</b> <code>").append(escapeHtml(kgsDTO.getFormattedSureklilik())).append("</code>\n");
            sb.append("  🏦 <b>Top 5 Yoğunlaşma:</b> <code>").append(escapeHtml(kgsDTO.getFormattedYogunlasma())).append("</code>\n");

            if (kgsDTO.getAliciKurumSayisi() != null && kgsDTO.getAliciKurumSayisi() > 0) {
                sb.append("  📊 <b>Çeşitlilik:</b> <code>").append(kgsDTO.getAliciKurumSayisi()).append(" kurum</code>\n");
            }
            if (kgsDTO.getConfidenceScore() != null && kgsDTO.getConfidenceScore() > 0) {
                sb.append("  🔒 <b>Güven Skoru:</b> <code>").append(kgsDTO.getConfidenceScore()).append("/100</code>\n");
            }

            String virman = buildVirmanWarningSection(kgsDTO);
            if (!virman.isEmpty()) {
                sb.append("\n").append(virman);
            }

            sb.append("\n");
            return sb.toString();
        } catch (Exception e) {
            log.debug("[FORMATTER] KGS section hatasi: {}", e.getMessage());
            return "";
        }
    }

    /** Virman uyarisi section'i. */
    private String buildVirmanWarningSection(KurumsalGucDTO kgsDTO) {
        if (kgsDTO == null || !kgsDTO.isVirmanDetected()) return "";
        if (!"HIGH".equals(kgsDTO.getVirmanSeverity())) return "";

        String typeName = switch (kgsDTO.getVirmanType()) {
            case "SUDDEN_ZERO" -> "Ani Sıfırlama";
            case "REVERSE_FLOW" -> "Ters Akış";
            case "VOLUME_SPIKE" -> "Hacim Spike";
            case "RECONCILIATION_MISMATCH" -> "AKD-Takas Uyumsuzluğu";
            default -> escapeHtml(kgsDTO.getVirmanType());
        };

        return "🚨 <b>VİRMAN UYARISI:</b>\n" +
               "  ⚠️ <b>Tür:</b> <i>" + typeName + "</i>\n" +
               "  🔴 <b>Ciddiyet:</b> <code>YÜKSEK</code>";
    }

    /** Fon pozisyonlari section'i. */
    private String buildFonPozisyonlariSection(String stockName) {
        if (fonPozisyonService == null) return "";
        try {
            List<FonPozisyon> pozisyonlar = fonPozisyonService.getFonPozisyonlari(stockName);
            if (pozisyonlar == null || pozisyonlar.isEmpty()) return "";

            StringBuilder sb = new StringBuilder();
            sb.append("📊 <b>FON POZİSYONLARI:</b>\n");

            int limit = Math.min(3, pozisyonlar.size());
            for (int i = 0; i < limit; i++) {
                FonPozisyon p = pozisyonlar.get(i);
                sb.append(String.format("  • <code>%s</code>: %s lot (%.2f%%)\n",
                        escapeHtml(p.getFonKodu()), formatNominal(p.getNominal()), p.getAgirlik()));
            }
            if (pozisyonlar.size() > 3) {
                sb.append(String.format("  ... ve %d fon daha\n", pozisyonlar.size() - 3));
            }
            sb.append("\n");
            return sb.toString();
        } catch (Exception e) {
            log.debug("[FORMATTER] Fon pozisyonu atlandi ({}): {}", stockName, e.getMessage());
            return "";
        }
    }

    /** Model portfoly section'i. */
    private String buildModelPortfolioSection(String stockName) {
        if (analystService == null) return "";
        try {
            List<BrokerageRating> ratings = analystService.getModelPortfolio(stockName);
            if (ratings == null || ratings.isEmpty()) return "";

            StringBuilder sb = new StringBuilder();
            sb.append("📊 <b>MODEL PORTFÖY:</b>\n");

            int limit = Math.min(5, ratings.size());
            for (int i = 0; i < limit; i++) {
                sb.append("  • <code>").append(escapeHtml(ratings.get(i).formatli())).append("</code>\n");
            }
            if (ratings.size() > 5) {
                sb.append(String.format("  ... ve %d kurum daha\n", ratings.size() - 5));
            }
            sb.append("\n");
            return sb.toString();
        } catch (Exception e) {
            log.debug("[FORMATTER] Model portfoly atlandi ({}): {}", stockName, e.getMessage());
            return "";
        }
    }

    /** Kurum dagitimi section'i. */
    private String buildKurumDagilimiSection(String stockName) {
        if (perStockAKDService == null) return "";
        try {
            List<StockBrokerInfo> brokers = perStockAKDService.getStockBrokerDistribution(stockName);
            if (brokers == null || brokers.isEmpty()) return "";

            StringBuilder sb = new StringBuilder();
            sb.append("🏢 <b>KURUM DAĞILIMI:</b>\n");
            for (StockBrokerInfo broker : brokers) {
                sb.append(String.format("  %s <code>%s</code>: %s TL\n",
                        escapeHtml(broker.getEmoji()), escapeHtml(broker.getBrokerName()), escapeHtml(broker.getFormattedVolume())));
            }
            sb.append("\n");
            return sb.toString();
        } catch (Exception e) {
            log.debug("[FORMATTER] Kurum dagitimi atlandi ({}): {}", stockName, e.getMessage());
            return "";
        }
    }

    /** Takas dagitimi section'i. */
    private String buildTakasDagilimiSection(String stockName) {
        if (takasApiService == null || !takasApiService.isEnabled()) return "";
        try {
            java.time.LocalDate today = java.time.LocalDate.now(ISTANBUL_ZONE);
            List<TakasCustodianDTO> custodians = takasApiService.getCustodyData(stockName, today);
            if (custodians == null || custodians.isEmpty()) return "";

            List<TakasCustodianDTO> top = custodians.stream()
                    .filter(c -> c.getValue() != null && c.getValue() > 0)
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .limit(5)
                    .toList();
            if (top.isEmpty()) return "";

            StringBuilder sb = new StringBuilder();
            sb.append("📦 <b>TAKAS DAĞILIMI:</b>\n");
            for (TakasCustodianDTO c : top) {
                sb.append(String.format("  🏦 <code>%s</code>: %s", escapeHtml(c.getCustodianCode()), escapeHtml(c.getFormattedValue())));
                if (c.getPercentage() != null && c.getPercentage() > 0) {
                    sb.append(String.format(" (%.2f%%)", c.getPercentage() * 100));
                }
                sb.append("\n");
            }
            sb.append("\n");
            return sb.toString();
        } catch (Exception e) {
            log.debug("[FORMATTER] Takas dagitimi atlandi ({}): {}", stockName, e.getMessage());
            return "";
        }
    }

    // ==================== UTILITY ====================

    /** Nominal formatlama (4910000 → "4.9M"). */
    private String formatNominal(long nominal) {
        if (nominal >= 1_000_000) return String.format("%.1fM", nominal / 1_000_000.0);
        if (nominal >= 1_000) return String.format("%.1fK", nominal / 1_000.0);
        return String.valueOf(nominal);
    }

    /** HTML escape. */
    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
