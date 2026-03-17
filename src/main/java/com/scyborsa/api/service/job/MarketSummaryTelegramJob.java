package com.scyborsa.api.service.job;

import com.scyborsa.api.config.ScreenerScanBodyRegistry;
import com.scyborsa.api.config.TelegramConfig;
import com.scyborsa.api.dto.screener.ScanBodyDefinition;
import com.scyborsa.api.dto.screener.TvScreenerResponse;
import com.scyborsa.api.enums.ScreenerTypeEnum;
import com.scyborsa.api.service.screener.TradingViewScreenerClient;
import com.scyborsa.api.service.telegram.MarketSummaryTelegramBuilder;
import com.scyborsa.api.service.telegram.TelegramClient;
import com.scyborsa.api.utils.BistTradingCalendar;
import com.scyborsa.api.utils.ProfileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Piyasa Özeti Telegram gönderim job'u.
 *
 * <p>Saatte bir en çok yükselen, düşen ve hacimli hisseleri
 * Telegram'a gönderir. 3 ayrı TradingView scan çağrısı yapar.</p>
 *
 * @see MarketSummaryTelegramBuilder
 * @see TradingViewScreenerClient
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketSummaryTelegramJob {

    /** Ardisik scan cagrilari arasindaki bekleme suresi (ms). */
    private static final long SCAN_DELAY_MS = 2500;

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

            String message = builder.build(rising, falling, volume);
            if (message != null) {
                boolean sent = telegramClient.sendHtmlMessage(message);
                if (sent) {
                    log.info("[MARKET-SUMMARY-JOB] Piyasa özeti gönderildi");
                } else {
                    log.warn("[MARKET-SUMMARY-JOB] Mesaj gönderilemedi");
                }
            } else {
                log.debug("[MARKET-SUMMARY-JOB] Mesaj oluşturulamadı (veri yok)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[MARKET-SUMMARY-JOB] İş parçacığı kesildi");
        } catch (Exception e) {
            log.error("[MARKET-SUMMARY-JOB] Hata: {}", e.getMessage(), e);
        }
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
            log.warn("[MARKET-SUMMARY-JOB] Scan hatası ({}): {}", scanBody.name(), e.getMessage());
            return null;
        }
    }
}
