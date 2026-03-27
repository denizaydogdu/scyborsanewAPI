package com.scyborsa.api.service.job;

import com.scyborsa.api.config.ScreenerScanBodyRegistry;
import com.scyborsa.api.config.TelegramConfig;
import com.scyborsa.api.dto.screener.ScanBodyDefinition;
import com.scyborsa.api.dto.screener.TvScreenerResponse;
import com.scyborsa.api.enums.ScreenerTypeEnum;
import com.scyborsa.api.service.screener.TradingViewScreenerClient;
import com.scyborsa.api.service.telegram.MarketSummaryTelegramBuilder;
import com.scyborsa.api.service.telegram.TelegramClient;
import com.scyborsa.api.service.telegram.TelegramVolumeFormatter;
import com.scyborsa.api.service.telegram.infographic.MarketSummaryCardData;
import com.scyborsa.api.service.telegram.infographic.MarketSummaryCardRenderer;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Piyasa Özeti Telegram gönderim job'u.
 *
 * <p>Saatte bir en çok yükselen, düşen ve hacimli hisseleri
 * Telegram'a gönderir. 3 ayrı TradingView scan çağrısı yapar.
 * Öncelikle infografik kart (PNG) göndermeyi dener, başarısız
 * olursa text mesaja fallback yapar.</p>
 *
 * @see MarketSummaryTelegramBuilder
 * @see MarketSummaryCardRenderer
 * @see TradingViewScreenerClient
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketSummaryTelegramJob {

    /** Ardisik scan cagrilari arasindaki bekleme suresi (ms). */
    private static final long SCAN_DELAY_MS = 2500;

    /** Her kategori icin gosterilecek maksimum hisse sayisi. */
    private static final int TOP_LIMIT = 5;

    /** Saat formatlayici (HH:mm). */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /** Telegram mesaj gonderim istemcisi. */
    private final TelegramClient telegramClient;

    /** Telegram yapilandirma ayarlari. */
    private final TelegramConfig telegramConfig;

    /** Spring profil kontrol yardimcisi. */
    private final ProfileUtils profileUtils;

    /** TradingView screener API istemcisi. */
    private final TradingViewScreenerClient screenerClient;

    /** Screener scan body tanimlarini barindiran registry. */
    private final ScreenerScanBodyRegistry scanBodyRegistry;

    /** Piyasa ozeti Telegram mesaj olusturucu. */
    private final MarketSummaryTelegramBuilder builder;

    /** Piyasa özeti infografik kartı renderer (graceful degradation). */
    @Autowired(required = false)
    private MarketSummaryCardRenderer cardRenderer;

    /**
     * Piyasa özeti Telegram gönderimini tetikler.
     *
     * <p>SC ile birebir uyumlu 11 sabit zamanda çalışır:</p>
     * <ul>
     *   <li>Açılış: 09:57, 10:02, 10:15, 10:35</li>
     *   <li>Gün içi: 11:10, 11:30, 12:02-17:02 saatlik</li>
     *   <li>Kapanış: 17:32, 18:02, 18:12</li>
     * </ul>
     */
    @Schedules({
            // Açılış saatleri (SC off-cycle)
            @Scheduled(cron = "0 57 9 * * MON-FRI", zone = "Europe/Istanbul"),
            @Scheduled(cron = "0 2 10 * * MON-FRI", zone = "Europe/Istanbul"),
            @Scheduled(cron = "0 15 10 * * MON-FRI", zone = "Europe/Istanbul"),
            @Scheduled(cron = "0 35 10 * * MON-FRI", zone = "Europe/Istanbul"),
            // Gün içi (SC off-cycle)
            @Scheduled(cron = "0 10 11 * * MON-FRI", zone = "Europe/Istanbul"),
            @Scheduled(cron = "0 30 11 * * MON-FRI", zone = "Europe/Istanbul"),
            // Gün içi saatlik (12:02-17:02)
            @Scheduled(cron = "0 2 12-17 * * MON-FRI", zone = "Europe/Istanbul"),
            // Kapanış saatleri (SC off-cycle)
            @Scheduled(cron = "0 32 17 * * MON-FRI", zone = "Europe/Istanbul"),
            @Scheduled(cron = "0 2 18 * * MON-FRI", zone = "Europe/Istanbul"),
            @Scheduled(cron = "0 12 18 * * MON-FRI", zone = "Europe/Istanbul")
    })
    public void run() {
        if (!profileUtils.isProdProfile()) return;
        if (!telegramConfig.isEnabled()) return;
        if (!BistTradingCalendar.isNotOffDay()) return;

        try {
            List<ScanBodyDefinition> scanBodies = scanBodyRegistry.getScanBodies(ScreenerTypeEnum.MARKET_SUMMARY);

            Map<String, ScanBodyDefinition> byName = scanBodies.stream()
                    .collect(Collectors.toMap(ScanBodyDefinition::name, Function.identity()));

            ScanBodyDefinition risingBody = byName.get("BIST_RISING");
            ScanBodyDefinition fallingBody = byName.get("BIST_FALLING");
            ScanBodyDefinition volumeBody = byName.get("BIST_VOLUME");

            if (risingBody == null || fallingBody == null || volumeBody == null) {
                log.warn("[MARKET-SUMMARY-JOB] Eksik scan body: rising={}, falling={}, volume={}",
                        risingBody != null, fallingBody != null, volumeBody != null);
                return;
            }

            TvScreenerResponse rising = executeScanSafe(risingBody);
            Thread.sleep(SCAN_DELAY_MS);

            TvScreenerResponse falling = executeScanSafe(fallingBody);
            Thread.sleep(SCAN_DELAY_MS);

            TvScreenerResponse volume = executeScanSafe(volumeBody);

            boolean sent = false;

            // Infografik kart denemesi (birincil yol)
            if (cardRenderer != null && telegramConfig.getInfographic().isEnabled()) {
                try {
                    MarketSummaryCardData cardData = buildCardData(rising, falling, volume);
                    if (cardData != null) {
                        byte[] png = cardRenderer.renderCard(cardData);
                        if (png != null) {
                            sent = telegramClient.sendPhoto(png, "\uD83D\uDCCA <b>BIST Piyasa \u00d6zeti</b> | ScyBorsa Bot");
                            if (sent) {
                                log.info("[MARKET-SUMMARY-JOB] Infografik kart gonderildi ({} KB)",
                                        png.length / 1024);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("[MARKET-SUMMARY-JOB] Infografik kart olusturulamadi, text fallback: {}",
                            e.getMessage());
                }
            }

            // Fallback: text mesaj
            if (!sent) {
                String message = builder.build(rising, falling, volume);
                if (message != null) {
                    sent = telegramClient.sendHtmlMessage(message);
                }
            }

            if (sent) {
                long rateLimitMs = telegramConfig.getSendRateLimitMs();
                if (rateLimitMs > 0) Thread.sleep(rateLimitMs);
                telegramClient.sendHtmlMessage("****************************************");
                log.info("[MARKET-SUMMARY-JOB] Piyasa ozeti gonderildi");
            } else {
                log.debug("[MARKET-SUMMARY-JOB] Mesaj olusturulamadi (veri yok)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[MARKET-SUMMARY-JOB] Is parcacigi kesildi");
        } catch (Exception e) {
            log.error("[MARKET-SUMMARY-JOB] Hata: {}", e.getMessage(), e);
        }
    }

    /**
     * 3 tarama sonucundan infografik kart verisi oluşturur.
     *
     * <p>Her tarama sonucundan maks 5 hisse çıkarır. Ticker "BIST:THYAO"
     * formatından ayrıştırılır. Hacim Türkçe formatlanır.</p>
     *
     * @param rising  yükselen hisseler tarama sonucu
     * @param falling düşen hisseler tarama sonucu
     * @param volume  hacimli hisseler tarama sonucu
     * @return kart verisi, tüm listeler boşsa {@code null}
     */
    private MarketSummaryCardData buildCardData(TvScreenerResponse rising,
                                                 TvScreenerResponse falling,
                                                 TvScreenerResponse volume) {
        List<MarketSummaryCardData.StockItem> risingItems = parseStockItems(rising);
        List<MarketSummaryCardData.StockItem> fallingItems = parseStockItems(falling);
        List<MarketSummaryCardData.StockItem> volumeItems = parseStockItems(volume);

        if (risingItems.isEmpty() && fallingItems.isEmpty() && volumeItems.isEmpty()) {
            return null;
        }

        return MarketSummaryCardData.builder()
                .timestamp(LocalTime.now().format(TIME_FMT))
                .risingStocks(risingItems)
                .fallingStocks(fallingItems)
                .volumeStocks(volumeItems)
                .build();
    }

    /**
     * TvScreenerResponse'dan StockItem listesi çıkarır (maks 5).
     *
     * <p>d[] dizisi sırası: name(0), description(1), close(2), change(3), volume(4).</p>
     *
     * @param response tarama sonucu
     * @return hisse öğe listesi
     */
    private List<MarketSummaryCardData.StockItem> parseStockItems(TvScreenerResponse response) {
        if (response == null || response.getData() == null || response.getData().isEmpty()) {
            return List.of();
        }

        List<MarketSummaryCardData.StockItem> items = new ArrayList<>();
        for (var item : response.getData()) {
            List<Object> d = item.getD();
            if (d == null || d.size() < 5) continue;

            if (items.size() >= TOP_LIMIT) break;

            String ticker = extractTicker(item.getS());
            double close = toDouble(d.get(2));
            double change = toDouble(d.get(3));
            double vol = toDouble(d.get(4));

            items.add(MarketSummaryCardData.StockItem.builder()
                    .ticker(ticker)
                    .changePercent(change)
                    .price(close)
                    .volume(TelegramVolumeFormatter.formatVolumeTurkish(vol))
                    .build());
        }
        return items;
    }

    /**
     * "BIST:THYAO" formatından ticker'ı çıkarır.
     *
     * @param symbol tam sembol (ör: "BIST:THYAO")
     * @return ticker (ör: "THYAO")
     */
    private String extractTicker(String symbol) {
        if (symbol == null) return "";
        int idx = symbol.indexOf(':');
        return idx >= 0 ? symbol.substring(idx + 1) : symbol;
    }

    /**
     * Object değerini double'a dönüştürür.
     *
     * @param val kaynak değer
     * @return double değer, dönüştürülemezse 0
     */
    private double toDouble(Object val) {
        if (val instanceof Number) return ((Number) val).doubleValue();
        if (val instanceof String) {
            try { return Double.parseDouble((String) val); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    /**
     * Scan çağrısını güvenli şekilde yürütür.
     *
     * @param scanBody tarama gövdesi
     * @return tarama sonucu, hata durumunda null
     */
    private TvScreenerResponse executeScanSafe(ScanBodyDefinition scanBody) {
        try {
            return screenerClient.executeScan(scanBody);
        } catch (Exception e) {
            log.warn("[MARKET-SUMMARY-JOB] Scan hatasi ({}): {}", scanBody.name(), e.getMessage());
            return null;
        }
    }
}
