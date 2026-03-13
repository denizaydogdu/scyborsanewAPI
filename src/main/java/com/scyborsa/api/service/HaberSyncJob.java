package com.scyborsa.api.service;

import com.scyborsa.api.config.HaberSyncConfig;
import com.scyborsa.api.dto.kap.KapNewsItemDto;
import com.scyborsa.api.dto.kap.KapNewsResponseDto;
import com.scyborsa.api.model.haber.HaberDetay;
import com.scyborsa.api.repository.HaberDetayRepository;
import com.scyborsa.api.service.kap.KapNewsClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Haber senkronizasyon job'u — TradingView API'den headline sync + detail fetch.
 *
 * <p>Her 60 saniyede bir calisir (yapilandirmaya bagli):
 * <ol>
 *   <li>KAP, piyasa ve dunya haberlerinin basliklarini DB'ye kaydeder (duplicate detection ile)</li>
 *   <li>Detayi henuz cekilmemis haberlerin icerigini fetch eder (max N/cycle, rate limited)</li>
 *   <li>DB'deki haber sayisini {@code maxStored} sinirinda tutar (eski haberleri siler)</li>
 * </ol>
 * </p>
 *
 * @see HaberDetailFetcher
 * @see HaberSyncConfig
 * @see com.scyborsa.api.model.haber.HaberDetay
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HaberSyncJob {

    private final KapNewsClient kapNewsClient;
    private final HaberDetailFetcher haberDetailFetcher;
    private final HaberDetayRepository haberDetayRepository;
    private final HaberSyncConfig haberSyncConfig;

    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");

    /**
     * Ana sync cycle: headline sync + detail fetch + cleanup.
     *
     * <p>{@code haber.sync.enabled} false ise calismayi atlar.</p>
     */
    @Scheduled(fixedDelayString = "${haber.sync.fixed-delay:60000}")
    public void sync() {
        if (!haberSyncConfig.isEnabled()) return;

        log.debug("Haber sync basliyor...");
        int newCount = syncHeadlines();
        int fetchedCount = fetchDetails();
        cleanup();
        log.debug("Haber sync tamamlandi: {} yeni baslik, {} detay cekildi", newCount, fetchedCount);
    }

    /**
     * Tum kaynaklardan basliklari ceker ve DB'ye kaydeder.
     *
     * <p>KAP, piyasa ve dunya haberleri sirayla sync edilir.
     * Her kaynak icin {@code existsByNewsId} ile duplicate kontrol yapilir.</p>
     *
     * @return yeni eklenen haber sayisi
     */
    @Transactional
    public int syncHeadlines() {
        int count = 0;
        count += syncSource("KAP", kapNewsClient.fetchKapNews());
        count += syncSource("MARKET", kapNewsClient.fetchMarketNews());
        count += syncSource("WORLD", kapNewsClient.fetchWorldNews());
        return count;
    }

    /**
     * Tek kaynak icin basliklari sync eder.
     *
     * @param newsType haber turu (KAP, MARKET, WORLD)
     * @param response KapNewsClient'tan donen response
     * @return yeni eklenen haber sayisi
     */
    private int syncSource(String newsType, KapNewsResponseDto response) {
        if (response == null || response.getItems() == null) return 0;

        // Batch duplicate check — tek sorgu ile mevcut newsId'leri al
        List<String> incomingIds = response.getItems().stream()
                .map(KapNewsItemDto::getId)
                .filter(Objects::nonNull)
                .toList();
        Set<String> existingIds = incomingIds.isEmpty()
                ? Set.of()
                : haberDetayRepository.findExistingNewsIds(incomingIds);

        int count = 0;
        for (KapNewsItemDto item : response.getItems()) {
            if (item.getId() == null || existingIds.contains(item.getId())) {
                continue;
            }

            LocalDateTime publishedAt = item.getPublished() != null
                    ? LocalDateTime.ofInstant(Instant.ofEpochSecond(item.getPublished()), ISTANBUL)
                    : LocalDateTime.now(ISTANBUL);

            HaberDetay haber = HaberDetay.builder()
                    .newsId(item.getId())
                    .title(item.getTitle())
                    .provider(item.getProvider())
                    .storyPath(item.getStoryPath())
                    .published(publishedAt)
                    .fetched(false)
                    .newsType(newsType)
                    .createTime(LocalDateTime.now(ISTANBUL))
                    .build();

            haberDetayRepository.save(haber);
            count++;
        }
        return count;
    }

    /**
     * Detayi henuz cekilmemis haberlerin icerigini fetch eder (max N/cycle).
     *
     * <p>Her fetch arasinda {@code fetchDelay} ms bekleme yapilir (rate limiting).
     * Hata durumunda ilgili haber atlanir ve bir sonrakine gecilir.</p>
     *
     * @return fetch edilen haber sayisi
     */
    public int fetchDetails() {
        List<HaberDetay> unfetched = haberDetayRepository.findByFetchedFalseOrderByCreateTimeAsc();
        int limit = Math.min(unfetched.size(), haberSyncConfig.getMaxFetchPerCycle());
        int count = 0;

        for (int i = 0; i < limit; i++) {
            HaberDetay haber = unfetched.get(i);
            try {
                haberDetailFetcher.fetchSingleDetail(haber);
                count++;

                // Rate limiting
                if (i < limit - 1) {
                    Thread.sleep(haberSyncConfig.getFetchDelay());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Detay fetch hatasi [newsId={}]: {}", haber.getNewsId(), e.getMessage());
            }
        }
        return count;
    }

    /**
     * DB'de maxStored'dan fazla haber varsa en eskileri siler.
     *
     * <p>En yeni {@code maxStored} haberin ID'lerini alir ve geri kalanini siler.</p>
     */
    @Transactional
    public void cleanup() {
        if (haberSyncConfig.getMaxStored() <= 0) {
            log.warn("Haber cleanup atlaniyor: maxStored gecersiz ({})", haberSyncConfig.getMaxStored());
            return;
        }
        long totalCount = haberDetayRepository.count();
        if (totalCount <= haberSyncConfig.getMaxStored()) return;

        haberDetayRepository.cleanupOldNews(haberSyncConfig.getMaxStored());
        long afterCount = haberDetayRepository.count();
        log.info("Haber cleanup: {} haber silindi (toplam {} -> {})",
                totalCount - afterCount, totalCount, afterCount);
    }

}
