package com.scyborsa.api.service.enrichment;

import com.scyborsa.api.dto.enrichment.OrderbookResponseDto;
import com.scyborsa.api.enums.EnrichmentDataTypeEnum;
import com.scyborsa.api.model.StockModel;
import com.scyborsa.api.repository.EnrichmentCacheRepository;
import com.scyborsa.api.repository.StockModelRepository;
import com.scyborsa.api.utils.BistTradingCalendar;
import com.scyborsa.api.utils.ProfileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/**
 * Emir defteri (Orderbook) verilerini günlük olarak DB'ye senkronize eden scheduled job.
 *
 * <p>Her iş günü saat 18:30'da (Europe/Istanbul) çalışır — seans kapanışından (18:15)
 * 15 dakika sonra. Tüm aktif hisseler için Fintables API'den zenginleştirilmiş orderbook
 * verilerini çeker ve {@code enrichment_cache} tablosuna yazar.</p>
 *
 * <p><b>Özellikler:</b></p>
 * <ul>
 *   <li>İdempotent: Zaten cache'lenmiş hisseler atlanır (batch skip)</li>
 *   <li>Rate limit: Her hisse arasında 5 saniye bekleme (~900 hisse × 5s ≈ 75 dk)</li>
 *   <li>Guard clause: Sadece prod profilinde ve iş günlerinde çalışır</li>
 *   <li>Async: Scheduler thread'i bloklamaz, ayrı thread'de çalışır</li>
 *   <li>Cleanup: 30 günden eski kayıtlar {@link AkdTakasSyncJob} tarafından temizlenir</li>
 * </ul>
 *
 * @see OrderbookService#fetchAndEnrichFromApi(String)
 * @see OrderbookService#saveToDbCache(String, LocalDate, OrderbookResponseDto)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderbookSyncJob {

    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");

    /** Her hisse arasında bekleme süresi (5 saniye). Orderbook tek endpoint, AKD+Takas'tan daha hafif. */
    private static final int RATE_LIMIT_MS = 5_000;

    private final StockModelRepository stockModelRepository;
    private final OrderbookService orderbookService;
    private final EnrichmentCacheRepository cacheRepository;
    private final ProfileUtils profileUtils;

    /**
     * Günlük orderbook senkronizasyonunu tetikler.
     *
     * <p>Her iş günü 18:30'da çalışır. Prod profili ve iş günü kontrolü yapar.
     * Asıl işi {@link #doSync()} async metoduna delege eder — scheduler thread'i
     * bloklanmaz.</p>
     */
    @Scheduled(cron = "0 30 18 * * MON-FRI", zone = "Europe/Istanbul")
    public void syncOrderbookData() {
        if (!profileUtils.isProdProfile()) {
            log.debug("[ORDERBOOK-SYNC] Prod değil, senkronizasyon atlanıyor");
            return;
        }

        if (!BistTradingCalendar.isTradingDay()) {
            log.debug("[ORDERBOOK-SYNC] Tatil günü, senkronizasyon atlanıyor");
            return;
        }

        doSync();
    }

    /**
     * Asıl senkronizasyon döngüsü.
     *
     * <p>Tüm aktif hisseler için sırayla orderbook verilerini Fintables API'den çeker,
     * DB'ye yazar. DB'de bugün zaten cache'lenmiş hisseler atlanır (batch skip).
     * Rate limit için her hisse arasında 5 saniye bekler.</p>
     */
    @Async
    public void doSync() {
        LocalDate today = LocalDate.now(ISTANBUL_ZONE);

        // Batch skip: DB'de bugün zaten cache'lenmiş hisseler
        Set<String> cached = cacheRepository.findCachedStockCodes(today, EnrichmentDataTypeEnum.ORDERBOOK);
        List<StockModel> stocks = stockModelRepository.findActiveStocks();

        log.info("[ORDERBOOK-SYNC] Başlıyor: {} hisse, cached={}", stocks.size(), cached.size());

        int saved = 0, skipped = 0, errors = 0;

        for (StockModel stock : stocks) {
            String code = stock.getStockName();

            if (cached.contains(code)) {
                skipped++;
                continue;
            }

            try {
                OrderbookResponseDto data = orderbookService.fetchAndEnrichFromApi(code);
                if (data != null && data.getTransactions() != null && !data.getTransactions().isEmpty()) {
                    orderbookService.saveToDbCache(code, today, data);
                    saved++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                log.warn("[ORDERBOOK-SYNC] Hata: code={}, error={}", code, e.getMessage());
                errors++;
            }

            // Rate limit: 5 saniye bekleme
            try {
                Thread.sleep(RATE_LIMIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[ORDERBOOK-SYNC] Thread kesildi, senkronizasyon durduruluyor");
                break;
            }
        }

        log.info("[ORDERBOOK-SYNC] Tamamlandı: saved={}, skipped={}, errors={}", saved, skipped, errors);
    }
}
