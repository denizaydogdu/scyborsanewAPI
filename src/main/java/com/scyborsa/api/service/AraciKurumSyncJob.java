package com.scyborsa.api.service;

import com.scyborsa.api.dto.FintablesBrokerageDto;
import com.scyborsa.api.utils.BistTradingCalendar;
import com.scyborsa.api.utils.ProfileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fintables araci kurum listesini DB'ye sync eden scheduled job.
 *
 * <p>Her is gunu saat 09:00'da (Europe/Istanbul) calisir.
 * Fintables {@code /brokerages/} endpoint'inden araci kurum listesini ceker
 * ve {@link AraciKurumService#syncFromBrokerageList(List)} ile DB'ye sync eder.</p>
 *
 * <p><b>Guard clause'lar:</b></p>
 * <ol>
 *   <li>Prod profili aktif degilse atlanir</li>
 *   <li>BIST tatil gunuyse atlanir</li>
 * </ol>
 *
 * @see FintablesApiClient#getBrokerages()
 * @see AraciKurumService#syncFromBrokerageList(List)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AraciKurumSyncJob {

    private final FintablesApiClient fintablesApiClient;
    private final AraciKurumService araciKurumService;
    private final ProfileUtils profileUtils;

    /**
     * Fintables araci kurum senkronizasyonunu tetikleyen scheduled metod.
     *
     * <p>Her is gunu saat 09:00'da (Europe/Istanbul) calisir.
     * Prod profili aktif degilse veya tatil gunuyse islem atlanir.</p>
     *
     * <p>Olusan tum hatalar loglara yazilir; exception yukari firlatilmaz.</p>
     */
    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Europe/Istanbul")
    public void syncBrokeragesJob() {
        try {
            if (!profileUtils.isProdProfile()) {
                log.debug("[BROKERAGE-JOB] Prod degil, araci kurum senkronizasyonu atlaniyor");
                return;
            }

            if (!BistTradingCalendar.isNotOffDay()) {
                log.debug("[BROKERAGE-JOB] Tatil gunu, araci kurum senkronizasyonu atlaniyor");
                return;
            }

            log.info("[BROKERAGE-JOB] Araci kurum senkronizasyonu baslatildi (09:00)");
            List<FintablesBrokerageDto> brokerages = fintablesApiClient.getBrokerages();
            log.info("[BROKERAGE-JOB] Fintables'dan {} araci kurum alindi", brokerages.size());

            araciKurumService.syncFromBrokerageList(brokerages);
            log.info("[BROKERAGE-JOB] Araci kurum senkronizasyonu tamamlandi");

        } catch (Exception e) {
            log.error("[BROKERAGE-JOB] Araci kurum senkronizasyonu hatasi", e);
        }
    }
}
