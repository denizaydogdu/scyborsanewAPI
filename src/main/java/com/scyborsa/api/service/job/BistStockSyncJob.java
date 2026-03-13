package com.scyborsa.api.service.job;

import com.scyborsa.api.service.BistStockSyncService;
import com.scyborsa.api.utils.BistTradingCalendar;
import com.scyborsa.api.utils.ProfileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * BIST hisse verilerini DB'ye kaydeden scheduled job.
 *
 * Çalışma zamanı: 09:25 (Pazartesi-Cuma), Europe/Istanbul
 * BIST açılış saati 09:40, öncesinde hazırlık yapılır.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BistStockSyncJob {

    private final BistStockSyncService bistStockSyncService;
    private final ProfileUtils profileUtils;

    /**
     * BIST hisse senkronizasyonunu tetikleyen scheduled metod.
     *
     * <p>Her iş günü saat 09:25'te (Europe/Istanbul) çalışır.
     * Prod profili aktif değilse veya tatil günüyse işlem atlanır.</p>
     *
     * <p>Oluşan tüm hatalar loglara yazılır; exception yukarı fırlatılmaz.</p>
     */
    @Scheduled(cron = "0 25 09 * * MON-FRI", zone = "Europe/Istanbul")
    public void syncStocksJob() {
        try {
            if (!profileUtils.isProdProfile()) {
                log.debug("[STOCK-JOB] Prod değil, hisse senkronizasyonu atlanıyor");
                return;
            }

            if (!BistTradingCalendar.isNotOffDay()) {
                log.debug("[STOCK-JOB] Tatil günü, hisse senkronizasyonu atlanıyor");
                return;
            }

            log.info("[STOCK-JOB] BIST hisse senkronizasyonu başlatıldı (09:25)");
            bistStockSyncService.syncAllStocks();
            log.info("[STOCK-JOB] BIST hisse senkronizasyonu tamamlandı");

        } catch (Exception e) {
            log.error("[STOCK-JOB] Hisse senkronizasyonu hatası", e);
        }
    }
}
