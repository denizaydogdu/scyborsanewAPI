package com.scyborsa.api.service.enrichment;

import com.scyborsa.api.dto.enrichment.BrokerageAkdListResponseDto;
import com.scyborsa.api.dto.enrichment.FintablesBrokerageAkdListDto;
import com.scyborsa.api.service.client.FintablesApiClient;
import com.scyborsa.api.utils.BistCacheUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Piyasa geneli araci kurum AKD dagilim listesi servisi.
 *
 * <p>Fintables API'den tum araci kurumlarin toplu AKD verisini ceker,
 * dulestirir ve dinamik TTL'li volatile cache ile sunar
 * (seans ici 60s, seans disi bir sonraki seans acilisina kadar, minimum 1 saat).</p>
 *
 * @see FintablesApiClient#getBrokerageAkdList(String, String)
 * @see BrokerageAkdListResponseDto
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrokerageAkdListService {

    /** Fintables API istemcisi. */
    private final FintablesApiClient fintablesApiClient;

    /** Seans ici cache TTL (milisaniye): 60 saniye. */
    private static final long CACHE_TTL_LIVE_MS = 60_000;

    /** Minimum seans disi cache TTL (milisaniye): 1 saat (guvenlik alt siniri). */
    private static final long MIN_CACHE_TTL_OFFHOURS_MS = 3_600_000;

    /** Soguk baslatma hatasi sonrasi minimum yeniden deneme gecikmesi (milisaniye): 30 saniye. */
    private static final long COLD_START_RETRY_MS = 30_000L;

    /** Cache: son basarili API response. */
    private volatile BrokerageAkdListResponseDto cachedResponse;

    /** Cache: son sorgulan tarih (YYYY-MM-DD). */
    private volatile String cachedDate;

    /** Cache: son guncelleme zamani (epoch millis). */
    private volatile long cacheTimestamp;

    /** Cache yenileme icin kilit nesnesi. */
    private final Object cacheLock = new Object();

    /**
     * Piyasa geneli araci kurum AKD dagilim listesini getirir.
     * Dinamik TTL'li volatile cache kullanir (seans ici 60s, seans disi bir sonraki seans acilisina kadar).
     *
     * @param date tarih (YYYY-MM-DD, null ise bugun veya onceki islem gunu)
     * @return zenginlestirilmis AKD dagilim listesi
     */
    public BrokerageAkdListResponseDto getAkdList(String date) {
        String resolvedDate = BistCacheUtils.resolveDate(date);
        long ttl = BistCacheUtils.getDynamicOffhoursTTL(CACHE_TTL_LIVE_MS, MIN_CACHE_TTL_OFFHOURS_MS);
        long now = System.currentTimeMillis();

        // Fast path: volatile cache hit (lock-free)
        if (cachedResponse != null
                && resolvedDate.equals(cachedDate)
                && (now - cacheTimestamp) < ttl) {
            return cachedResponse;
        }

        synchronized (cacheLock) {
            // Re-check inside lock (DCL)
            ttl = BistCacheUtils.getDynamicOffhoursTTL(CACHE_TTL_LIVE_MS, MIN_CACHE_TTL_OFFHOURS_MS);
            now = System.currentTimeMillis();
            if (cachedResponse != null
                    && resolvedDate.equals(cachedDate)
                    && (now - cacheTimestamp) < ttl) {
                return cachedResponse;
            }

            try {
                FintablesBrokerageAkdListDto raw = fintablesApiClient.getBrokerageAkdList(resolvedDate, resolvedDate);
                BrokerageAkdListResponseDto response = transform(raw, resolvedDate);

                // Cache guncelle
                this.cachedResponse = response;
                this.cachedDate = resolvedDate;
                this.cacheTimestamp = System.currentTimeMillis();

                return response;
            } catch (Exception e) {
                log.error("[BROKERAGE-AKD] Veri alinamadi (date={})", resolvedDate, e);
                if (cachedResponse != null) {
                    // Mevcut cache var — tam TTL backoff (retry storm engeli)
                    this.cacheTimestamp = System.currentTimeMillis();
                    log.warn("[BROKERAGE-AKD] Mevcut cache korunuyor (date={}). TTL sonrasi yeniden denenecek.", cachedDate);
                    return cachedResponse;
                }
                // Soguk baslatma hatasi — 30 saniye sonra yeniden dene
                this.cacheTimestamp = System.currentTimeMillis() - ttl + COLD_START_RETRY_MS;
                log.warn("[BROKERAGE-AKD] Soguk baslatmada veri cekilemedi (date={}), 30 saniye sonra yeniden denenecek.", resolvedDate);
                return buildEmptyResponse(resolvedDate);
            }
        }
    }

    /**
     * Ham Fintables DTO'sunu zenginlestirilmis response DTO'ya donusturur.
     *
     * @param raw  Fintables API'den gelen ham veri
     * @param date verinin ait oldugu tarih
     * @return zenginlestirilmis response DTO
     */
    private BrokerageAkdListResponseDto transform(FintablesBrokerageAkdListDto raw, String date) {
        if (raw == null || raw.getResults() == null) {
            return buildEmptyResponse(date);
        }

        List<BrokerageAkdListResponseDto.BrokerageAkdItemDto> items = raw.getResults().stream()
                .map(item -> BrokerageAkdListResponseDto.BrokerageAkdItemDto.builder()
                        .code(item.getCode())
                        .title(item.getTitle())
                        .shortTitle(item.getShortTitle())
                        .logoUrl(item.getLogo())
                        .buyVolume(item.getBuy() != null ? item.getBuy().getVolume() : 0)
                        .buyPercentage(item.getBuy() != null ? item.getBuy().getPercentage() * 100 : 0)
                        .sellVolume(item.getSell() != null ? item.getSell().getVolume() : 0)
                        .sellPercentage(item.getSell() != null ? item.getSell().getPercentage() * 100 : 0)
                        .netVolume(item.getNet() != null ? item.getNet().getVolume() : 0)
                        .netPercentage(item.getNet() != null ? item.getNet().getPercentage() * 100 : 0)
                        .totalVolume(item.getTotal() != null ? item.getTotal().getVolume() : 0)
                        .totalPercentage(item.getTotal() != null ? item.getTotal().getPercentage() * 100 : 0)
                        .build())
                .sorted(Comparator.comparingLong(BrokerageAkdListResponseDto.BrokerageAkdItemDto::getTotalVolume).reversed())
                .collect(Collectors.toList());

        return BrokerageAkdListResponseDto.builder()
                .items(items)
                .dataDate(date)
                .formattedDataDate(BistCacheUtils.formatTurkishDate(date))
                .totalCount(items.size())
                .build();
    }

    /**
     * Bos response olusturur.
     *
     * @param date verinin ait oldugu tarih (nullable)
     * @return tum listeleri bos olan response
     */
    private BrokerageAkdListResponseDto buildEmptyResponse(String date) {
        return BrokerageAkdListResponseDto.builder()
                .items(Collections.emptyList())
                .dataDate(date != null ? date : "")
                .formattedDataDate(date != null ? BistCacheUtils.formatTurkishDate(date) : "")
                .totalCount(0)
                .build();
    }
}
