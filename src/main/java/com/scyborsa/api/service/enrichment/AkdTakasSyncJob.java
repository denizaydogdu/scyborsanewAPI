package com.scyborsa.api.service.enrichment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.dto.enrichment.AkdResponseDto;
import com.scyborsa.api.dto.enrichment.TakasResponseDto;
import com.scyborsa.api.enums.EnrichmentDataTypeEnum;
import com.scyborsa.api.model.EnrichmentCache;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/**
 * AKD ve Takas verilerini günlük olarak DB'ye senkronize eden scheduled job.
 *
 * <p>Her iş günü saat 19:00'da (Europe/Istanbul) çalışır.
 * Tüm aktif hisseler için Fintables API'den zenginleştirilmiş AKD ve Takas
 * verilerini çeker ve {@code enrichment_cache} tablosuna yazar.</p>
 *
 * <p><b>Özellikler:</b></p>
 * <ul>
 *   <li>İdempotent: Zaten cache'lenmiş hisseler atlanır (batch skip)</li>
 *   <li>Rate limit: Her hisse arasında 10 saniye bekleme</li>
 *   <li>Eski veri temizleme: 30 günden eski kayıtlar silinir</li>
 *   <li>Guard clause: Sadece prod profilinde ve iş günlerinde çalışır</li>
 *   <li>Async: Scheduler thread'i bloklamaz, ayrı thread'de çalışır</li>
 * </ul>
 *
 * @see AkdService#fetchAndEnrichFromApi(String)
 * @see TakasService#fetchAndEnrichFromApi(String)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AkdTakasSyncJob {

    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");
    private static final int RATE_LIMIT_MS = 10_000;
    private static final int CLEANUP_DAYS = 30;

    private final AkdService akdService;
    private final TakasService takasService;
    private final EnrichmentCacheRepository cacheRepository;
    private final StockModelRepository stockModelRepository;
    private final ProfileUtils profileUtils;
    private final ObjectMapper objectMapper;

    /**
     * Günlük AKD ve Takas senkronizasyonunu tetikler.
     *
     * <p>Her iş günü 19:00'da çalışır. Prod profili ve iş günü kontrolü yapar.
     * Asıl işi {@link #doSync()} async metoduna delege eder — scheduler thread'i
     * bloklanmaz.</p>
     */
    @Scheduled(cron = "0 0 19 * * MON-FRI", zone = "Europe/Istanbul")
    public void syncDailyData() {
        if (!profileUtils.isProdProfile()) {
            log.debug("[ENRICHMENT-SYNC] Prod değil, senkronizasyon atlanıyor");
            return;
        }

        if (!BistTradingCalendar.isTradingDay()) {
            log.debug("[ENRICHMENT-SYNC] Tatil günü, senkronizasyon atlanıyor");
            return;
        }

        doSync();
    }

    /**
     * Asıl senkronizasyon döngüsü.
     *
     * <p>Tüm aktif hisseler için sırayla AKD ve Takas verilerini çeker,
     * DB'ye yazar. Rate limit için her hisse arasında 10 saniye bekler.</p>
     */
    @Async
    public void doSync() {
        LocalDate today = LocalDate.now(ISTANBUL_ZONE);

        // Batch skip: zaten cache'lenmiş hisseleri topla
        Set<String> cachedAkd = cacheRepository.findCachedStockCodes(today, EnrichmentDataTypeEnum.AKD);
        Set<String> cachedTakas = cacheRepository.findCachedStockCodes(today, EnrichmentDataTypeEnum.TAKAS);

        List<StockModel> stocks = stockModelRepository.findActiveStocks();
        log.info("[ENRICHMENT-SYNC] Başlıyor: {} hisse, cachedAkd={}, cachedTakas={}",
                stocks.size(), cachedAkd.size(), cachedTakas.size());

        int akdSaved = 0, takasSaved = 0, akdSkipped = 0, takasSkipped = 0, errors = 0;

        for (StockModel stock : stocks) {
            String code = stock.getStockName();

            // AKD
            if (!cachedAkd.contains(code)) {
                try {
                    AkdResponseDto akdData = akdService.fetchAndEnrichFromApi(code);
                    if (akdData != null && akdData.getAlicilar() != null && !akdData.getAlicilar().isEmpty()) {
                        saveToCache(code, today, EnrichmentDataTypeEnum.AKD, akdData);
                        akdSaved++;
                    }
                } catch (Exception e) {
                    log.warn("[ENRICHMENT-SYNC] AKD hata: {}", code, e);
                    errors++;
                }
            } else {
                akdSkipped++;
            }

            // TAKAS
            if (!cachedTakas.contains(code)) {
                try {
                    TakasResponseDto takasData = takasService.fetchAndEnrichFromApi(code);
                    if (takasData != null && takasData.getCustodians() != null && !takasData.getCustodians().isEmpty()) {
                        saveToCache(code, today, EnrichmentDataTypeEnum.TAKAS, takasData);
                        takasSaved++;
                    }
                } catch (Exception e) {
                    log.warn("[ENRICHMENT-SYNC] Takas hata: {}", code, e);
                    errors++;
                }
            } else {
                takasSkipped++;
            }

            // Rate limit: 10 saniye bekleme
            try {
                Thread.sleep(RATE_LIMIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[ENRICHMENT-SYNC] Thread kesildi, senkronizasyon durduruluyor");
                break;
            }
        }

        // Eski verileri temizle (30 gün) — @Transactional repository metodu doğrudan çağrılır
        cleanupOldData(today);

        log.info("[ENRICHMENT-SYNC] Tamamlandı: akdSaved={}, akdSkipped={}, takasSaved={}, takasSkipped={}, errors={}",
                akdSaved, akdSkipped, takasSaved, takasSkipped, errors);
    }

    /**
     * Zenginleştirilmiş veriyi JSON olarak DB'ye kaydeder.
     *
     * @param stockCode hisse kodu
     * @param date      cache tarihi
     * @param type      veri tipi
     * @param data      serileştirilecek veri
     */
    private void saveToCache(String stockCode, LocalDate date,
                             EnrichmentDataTypeEnum type, Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            EnrichmentCache cache = EnrichmentCache.builder()
                    .stockCode(stockCode)
                    .cacheDate(date)
                    .dataType(type)
                    .jsonData(json)
                    .build();
            cacheRepository.save(cache);
        } catch (JsonProcessingException e) {
            log.error("[ENRICHMENT-SYNC] JSON serialize hata: {} {}", stockCode, type, e);
        } catch (Exception e) {
            log.warn("[ENRICHMENT-SYNC] Cache kaydetme hatası: {} {} ({})",
                    stockCode, type, e.getMessage());
        }
    }

    /**
     * 30 günden eski cache kayıtlarını temizler.
     *
     * <p>deleteOlderThan repository metodu kendi @Transactional'ına sahip,
     * self-call bypass sorunu yok.</p>
     *
     * @param today referans tarih
     */
    private void cleanupOldData(LocalDate today) {
        try {
            int deleted = cacheRepository.deleteOlderThan(today.minusDays(CLEANUP_DAYS));
            if (deleted > 0) {
                log.info("[ENRICHMENT-SYNC] {} eski cache kaydı silindi", deleted);
            }
        } catch (Exception e) {
            log.warn("[ENRICHMENT-SYNC] Eski veri temizleme hatası", e);
        }
    }
}
