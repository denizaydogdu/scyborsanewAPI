package com.scyborsa.api.service;

import com.scyborsa.api.dto.enrichment.BrokerageAkdDetailResponseDto;
import com.scyborsa.api.dto.enrichment.BrokerageAkdDetailResponseDto.StockAkdItemDto;
import com.scyborsa.api.dto.enrichment.FintablesBrokerageAkdDetailDto;
import com.scyborsa.api.repository.AraciKurumRepository;
import com.scyborsa.api.utils.BistCacheUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Araci kurum bazli AKD detay servisi.
 *
 * <p>Fintables API'den belirli bir araci kurumun hisse bazli AKD (Araci Kurum Dagilimi)
 * verisini ceker, donusturur ve adaptif TTL'li ConcurrentHashMap cache ile sunar
 * (seans ici 60s, seans disi/tatil 1 saat).</p>
 *
 * <p>Cache yapisi: Her araci kurum + tarih kombinasyonu icin ayri cache entry'si tutulur.
 * Farkli araci kurumlar esanli olarak sorgulanabilir.</p>
 *
 * @see FintablesApiClient#getBrokerageAkdDetail(String, String, String)
 * @see BrokerageAkdDetailResponseDto
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrokerageAkdDetailService {

    private final FintablesApiClient fintablesApiClient;
    private final AraciKurumRepository araciKurumRepository;
    private final Bist100Service bist100Service;

    private static final long CACHE_TTL_LIVE_MS = 60_000;      // Seans ici: 60 saniye
    private static final long CACHE_TTL_OFFHOURS_MS = 3_600_000; // Seans disi: 1 saat
    private static final int MAX_CACHE_SIZE = 100;

    /** Cache: key = brokerageCode + "_" + date, value = CachedEntry. */
    private final ConcurrentHashMap<String, CachedEntry> cache = new ConcurrentHashMap<>();

    /**
     * Belirli bir araci kurumun hisse bazli AKD detay verisini getirir.
     *
     * <p>Adaptif TTL ile ConcurrentHashMap cache kullanir (seans ici 60s, seans disi 1 saat). Cache key'i
     * {@code brokerageCode + "_" + resolvedDate} formatindadir.</p>
     *
     * <p>Hata durumunda eski cache varsa onu dondurur, yoksa bos response olusturur.</p>
     *
     * @param brokerageCode araci kurum kodu (orn: "MLB", "YKR", "GAR")
     * @param date          tarih (YYYY-MM-DD, null ise bugun veya onceki islem gunu)
     * @return zenginlestirilmis AKD detay response DTO'su
     */
    public BrokerageAkdDetailResponseDto getAkdDetail(String brokerageCode, String date) {
        String resolvedDate = BistCacheUtils.resolveDate(date);
        String cacheKey = brokerageCode + "_" + resolvedDate;

        // Cache hit kontrolu
        CachedEntry entry = cache.get(cacheKey);
        if (entry != null && (System.currentTimeMillis() - entry.timestamp) < BistCacheUtils.getAdaptiveTTL(CACHE_TTL_LIVE_MS, CACHE_TTL_OFFHOURS_MS)) {
            return entry.response;
        }

        try {
            FintablesBrokerageAkdDetailDto raw =
                    fintablesApiClient.getBrokerageAkdDetail(brokerageCode, resolvedDate, resolvedDate);
            BrokerageAkdDetailResponseDto response = transform(brokerageCode, raw);

            // Cache boyut kontrolu — max asilirsa en eski entry'yi cikar
            if (cache.size() >= MAX_CACHE_SIZE) {
                cache.entrySet().stream()
                        .min(Comparator.comparingLong(e -> e.getValue().timestamp))
                        .ifPresent(e -> {
                            log.debug("[BROKERAGE-AKD-DETAIL] Cache boyut sınırı, en eski giriş çıkarılıyor: {}", e.getKey());
                            cache.remove(e.getKey());
                        });
            }

            // Cache guncelle
            cache.put(cacheKey, new CachedEntry(response, System.currentTimeMillis()));

            return response;
        } catch (Exception e) {
            log.error("[BROKERAGE-AKD-DETAIL] Veri alinamadi (brokerage={}, date={})",
                    brokerageCode, resolvedDate, e);

            // Eski cache varsa don (stale)
            if (entry != null) {
                log.warn("[BROKERAGE-AKD-DETAIL] Eski cache donduruluyor (brokerage={}, date={})",
                        brokerageCode, resolvedDate);
                return entry.response;
            }

            return buildEmptyResponse(brokerageCode);
        }
    }

    /**
     * Ham Fintables DTO'sunu zenginlestirilmis response DTO'ya donusturur.
     *
     * <p>Yuzde degerlerini 100 ile carpar, hisseleri toplam hacme gore azalan sirayla siralar,
     * toplam net hacim hesaplar ve tarihi Turkce formatlar.</p>
     *
     * @param brokerageCode araci kurum kodu
     * @param raw           Fintables API'den gelen ham veri
     * @return zenginlestirilmis response DTO
     */
    private BrokerageAkdDetailResponseDto transform(String brokerageCode, FintablesBrokerageAkdDetailDto raw) {
        if (raw == null || raw.getResults() == null) {
            return buildEmptyResponse(brokerageCode);
        }

        List<StockAkdItemDto> items = raw.getResults().stream()
                .map(item -> StockAkdItemDto.builder()
                        .code(item.getCode())
                        .buyVolume(item.getBuy() != null ? item.getBuy().getVolume() : 0)
                        .buyPercentage(item.getBuy() != null ? item.getBuy().getPercentage() * 100 : 0)
                        .buySize(item.getBuy() != null ? item.getBuy().getSize() : 0)
                        .sellVolume(item.getSell() != null ? item.getSell().getVolume() : 0)
                        .sellPercentage(item.getSell() != null ? item.getSell().getPercentage() * 100 : 0)
                        .sellSize(item.getSell() != null ? item.getSell().getSize() : 0)
                        .netVolume(item.getNet() != null ? item.getNet().getVolume() : 0)
                        .netPercentage(item.getNet() != null ? item.getNet().getPercentage() * 100 : 0)
                        .netSize(item.getNet() != null ? item.getNet().getSize() : 0)
                        .totalVolume(item.getTotal() != null ? item.getTotal().getVolume() : 0)
                        .totalPercentage(item.getTotal() != null ? item.getTotal().getPercentage() * 100 : 0)
                        .totalSize(item.getTotal() != null ? item.getTotal().getSize() : 0)
                        .cost(item.getTotal() != null ? item.getTotal().getCost() : 0)
                        .build())
                .sorted(Comparator.comparingLong(StockAkdItemDto::getTotalVolume).reversed())
                .collect(Collectors.toList());

        // Logoid enrichment
        Map<String, String> logoidMap = bist100Service.getStockLogoidMap();
        items.forEach(item -> item.setLogoid(logoidMap.getOrDefault(item.getCode(), null)));

        // Toplam hacim hesaplamalari
        long totalBuyVolume = (long) raw.getTotalBuyVolume();
        long totalSellVolume = (long) raw.getTotalSellVolume();
        long totalNetVolume = totalBuyVolume - totalSellVolume;

        // Tarih formatlama — API "2026-03-10 00:00:00" formatinda donebilir
        String dataDate = extractDate(raw.getStart());

        BrokerageAkdDetailResponseDto response = BrokerageAkdDetailResponseDto.builder()
                .brokerageCode(brokerageCode)
                .items(items)
                .dataDate(dataDate)
                .formattedDataDate(BistCacheUtils.formatTurkishDate(dataDate))
                .totalBuyVolume(totalBuyVolume)
                .totalSellVolume(totalSellVolume)
                .totalNetVolume(totalNetVolume)
                .stockCount(items.size())
                .build();

        // Kurum metadata
        araciKurumRepository.findByCode(brokerageCode).ifPresent(kurum -> {
            response.setBrokerageTitle(kurum.getTitle());
            response.setBrokerageShortTitle(kurum.getShortTitle());
            response.setBrokerageLogoUrl(kurum.getLogoUrl());
        });

        return response;
    }

    /**
     * API tarih string'inden yyyy-MM-dd kismini cikarir.
     *
     * <p>"2026-03-10 00:00:00" formatini "2026-03-10" formatina donusturur.
     * Bos veya null ise bos string dondurur.</p>
     *
     * @param rawDate API'den gelen tarih string'i
     * @return yyyy-MM-dd formatinda tarih
     */
    private String extractDate(String rawDate) {
        if (rawDate == null || rawDate.isBlank()) {
            return "";
        }
        // "2026-03-10 00:00:00" -> "2026-03-10"
        return rawDate.length() >= 10 ? rawDate.substring(0, 10) : rawDate;
    }

    /**
     * Bos response olusturur. Hata durumlarinda veya veri bulunamadiginda kullanilir.
     *
     * <p>Kurum metadata (title, shortTitle, logoUrl) DB'den doldurulur,
     * boylece UI tarafinda veri olmasa bile kurum bilgileri gorunur.</p>
     *
     * @param brokerageCode araci kurum kodu
     * @return tum listeleri bos, degerleri sifir olan response (kurum metadata dahil)
     */
    private BrokerageAkdDetailResponseDto buildEmptyResponse(String brokerageCode) {
        BrokerageAkdDetailResponseDto response = BrokerageAkdDetailResponseDto.builder()
                .brokerageCode(brokerageCode)
                .items(Collections.emptyList())
                .dataDate("")
                .formattedDataDate("")
                .totalBuyVolume(0)
                .totalSellVolume(0)
                .totalNetVolume(0)
                .stockCount(0)
                .build();

        // Kurum metadata
        araciKurumRepository.findByCode(brokerageCode).ifPresent(kurum -> {
            response.setBrokerageTitle(kurum.getTitle());
            response.setBrokerageShortTitle(kurum.getShortTitle());
            response.setBrokerageLogoUrl(kurum.getLogoUrl());
        });

        return response;
    }

    /**
     * Cache entry'si. Response ve timestamp (milisaniye) tutar.
     */
    private static class CachedEntry {
        final BrokerageAkdDetailResponseDto response;
        final long timestamp;

        CachedEntry(BrokerageAkdDetailResponseDto response, long timestamp) {
            this.response = response;
            this.timestamp = timestamp;
        }
    }
}
