package com.scyborsa.api.service.enrichment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.dto.enrichment.FintablesOrderbookResponseDto;
import com.scyborsa.api.dto.enrichment.OrderbookResponseDto;
import com.scyborsa.api.dto.enrichment.OrderbookResponseDto.OrderbookTransactionDto;
import com.scyborsa.api.enums.EnrichmentDataTypeEnum;
import com.scyborsa.api.model.AraciKurum;
import com.scyborsa.api.model.EnrichmentCache;
import com.scyborsa.api.repository.AraciKurumRepository;
import com.scyborsa.api.repository.EnrichmentCacheRepository;
import com.scyborsa.api.service.client.FintablesApiClient;
import com.scyborsa.api.utils.AkdTakasTimeResolver;
import com.scyborsa.api.utils.AkdTakasTimeResolver.ReadStrategy;
import com.scyborsa.api.utils.BistCacheUtils;
import com.scyborsa.api.utils.BistTradingCalendar;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Emir defteri (orderbook) iş mantığı servisi.
 *
 * <p>Fintables API'den emir defteri işlem verilerini çeker, DB'deki
 * {@link AraciKurum} entity'leri ile zenginleştirir ve zaman bazlı
 * cache stratejisi uygular.</p>
 *
 * <p>Cache stratejisi:</p>
 * <ul>
 *   <li>Seans içi ({@code LIVE_API}): ConcurrentHashMap per-stock, 30s TTL</li>
 *   <li>Seans sonrası ({@code DB_FIRST_THEN_API}): DB'de varsa oku, yoksa API'den çek+kaydet</li>
 *   <li>Akşam ({@code DB_ONLY}): Bugünün DB cache'inden oku</li>
 *   <li>Gece/hafta sonu ({@code DB_PREVIOUS_DAY}): Önceki işlem gününün cache'inden oku</li>
 * </ul>
 *
 * @see AkdTakasTimeResolver
 * @see EnrichmentCacheRepository
 * @see FintablesApiClient#getOrderbook(String)
 * @see BistCacheUtils#getAdaptiveTTL(long, long)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderbookService {

    /** Istanbul saat dilimi. */
    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");

    /** Islem zamani formatlayici (HH:mm:ss). */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Seans içi cache TTL (30 saniye). */
    private static final long CACHE_TTL_LIVE_MS = 30_000;

    /** Seans dışı cache TTL (5 dakika). */
    private static final long CACHE_TTL_OFFHOURS_MS = 300_000;

    /** Maksimum cache boyutu. */
    private static final int MAX_CACHE_SIZE = 100;

    /** Fintables API istemcisi. */
    private final FintablesApiClient fintablesApiClient;

    /** Araci kurum repository (zenginlestirme icin). */
    private final AraciKurumRepository araciKurumRepository;

    /** Zenginlestirilmis veri cache repository. */
    private final EnrichmentCacheRepository cacheRepository;

    /** Zaman bazli okuma stratejisi belirleyici. */
    private final AkdTakasTimeResolver timeResolver;

    /** JSON serializasyon/deserializasyon icin ObjectMapper. */
    private final ObjectMapper objectMapper;

    /** Per-stock orderbook in-memory cache (seans ici LIVE_API icin). */
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * Hisse bazlı emir defteri işlemlerini zaman bazlı strateji ile getirir.
     *
     * <p>Günün saatine göre veriyi in-memory cache, DB veya API'den okur.
     * Seans içi canlı API (ConcurrentHashMap), seans dışı DB cache tercih edilir.</p>
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return zenginleştirilmiş orderbook response
     */
    public OrderbookResponseDto getOrderbookTransactions(String stockCode) {
        try {
            LocalDate today = LocalDate.now(ISTANBUL_ZONE);
            LocalTime now = LocalTime.now(ISTANBUL_ZONE);
            ReadStrategy strategy = timeResolver.resolve(today, now);

            log.debug("[Orderbook] stockCode={}, strategy={}", stockCode, strategy);

            switch (strategy) {
                case DB_PREVIOUS_DAY:
                    LocalDate prevDay = BistTradingCalendar.getPreviousTradingDay(today);
                    return readFromDbCache(stockCode, prevDay).orElse(emptyResponse());

                case LIVE_API:
                    return fetchWithMemoryCache(stockCode);

                case DB_FIRST_THEN_API:
                    return readFromDbCache(stockCode, today)
                            .orElseGet(() -> {
                                OrderbookResponseDto data = fetchFromApi(stockCode);
                                if (data != null && data.getTransactions() != null
                                        && !data.getTransactions().isEmpty()) {
                                    saveToDbCache(stockCode, today, data);
                                }
                                return data != null ? data : emptyResponse();
                            });

                case DB_ONLY:
                    return readFromDbCache(stockCode, today).orElse(emptyResponse());

                default:
                    return emptyResponse();
            }
        } catch (Exception e) {
            log.error("[Orderbook] Veri alınırken hata: stockCode={}", stockCode, e);
            return emptyResponse();
        }
    }

    /**
     * Fintables API'den orderbook verisini çeker ve zenginleştirir.
     *
     * <p>Sync job ve {@code DB_FIRST_THEN_API} stratejisi tarafından kullanılır.
     * In-memory cache KULLANMAZ — doğrudan API'ye gider.</p>
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return zenginleştirilmiş orderbook response, hata durumunda {@code null}
     */
    public OrderbookResponseDto fetchAndEnrichFromApi(String stockCode) {
        return fetchFromApi(stockCode);
    }

    /**
     * Zenginleştirilmiş orderbook verisini DB cache'e kaydeder.
     *
     * <p>İdempotent: Aynı stockCode+date+ORDERBOOK kombinasyonu zaten varsa yazmaz.</p>
     *
     * @param stockCode hisse kodu
     * @param date      cache tarihi
     * @param data      kaydedilecek veri
     */
    public void saveToDbCache(String stockCode, LocalDate date, OrderbookResponseDto data) {
        try {
            if (!cacheRepository.existsByStockCodeAndCacheDateAndDataType(
                    stockCode, date, EnrichmentDataTypeEnum.ORDERBOOK)) {
                String json = objectMapper.writeValueAsString(data);
                cacheRepository.save(EnrichmentCache.builder()
                        .stockCode(stockCode)
                        .cacheDate(date)
                        .dataType(EnrichmentDataTypeEnum.ORDERBOOK)
                        .jsonData(json)
                        .build());
                log.debug("[Orderbook] Cache'e kaydedildi: stockCode={}, date={}", stockCode, date);
            }
        } catch (JsonProcessingException e) {
            log.error("[Orderbook] Cache JSON serialize hata: stockCode={}", stockCode, e);
        } catch (Exception e) {
            log.warn("[Orderbook] Cache kaydetme hatası (concurrent write?): stockCode={}, date={}",
                    stockCode, date, e);
        }
    }

    // ─────────────────────────────────────────────────
    // Private methods
    // ─────────────────────────────────────────────────

    /**
     * In-memory ConcurrentHashMap cache üzerinden orderbook getirir.
     *
     * <p>Sadece {@code LIVE_API} stratejisi için kullanılır.
     * Cache hit varsa ve TTL geçmemişse döner, yoksa API'den çeker.</p>
     *
     * @param stockCode hisse kodu
     * @return zenginleştirilmiş orderbook response
     */
    private OrderbookResponseDto fetchWithMemoryCache(String stockCode) {
        CacheEntry entry = cache.get(stockCode);
        long ttl = BistCacheUtils.getAdaptiveTTL(CACHE_TTL_LIVE_MS, CACHE_TTL_OFFHOURS_MS);
        if (entry != null && !entry.isExpired(ttl)) {
            log.debug("[Orderbook] Cache hit: stockCode={}", stockCode);
            return entry.data;
        }

        try {
            OrderbookResponseDto result = fetchFromApi(stockCode);
            if (result != null) {
                putCache(stockCode, result);
                return result;
            }
            // API boş döndüyse stale cache kullan
            if (entry != null) {
                return entry.data;
            }
            OrderbookResponseDto empty = emptyResponse();
            putCache(stockCode, empty);
            return empty;
        } catch (Exception e) {
            log.error("[Orderbook] API hatası: stockCode={}", stockCode, e);
            if (entry != null) {
                log.warn("[Orderbook] Stale cache kullanılıyor: stockCode={}", stockCode);
                return entry.data;
            }
            return emptyResponse();
        }
    }

    /**
     * Fintables API'den orderbook verisini çeker ve broker bilgileri ile zenginleştirir.
     *
     * <p>Pure API fetch + enrich. Cache mantığı YOKTUR — çağıran method yönetir.</p>
     *
     * @param stockCode hisse kodu
     * @return zenginleştirilmiş orderbook response, API boş dönerse {@code null}
     */
    private OrderbookResponseDto fetchFromApi(String stockCode) {
        try {
            FintablesOrderbookResponseDto raw = fintablesApiClient.getOrderbook(stockCode);

            if (raw == null || raw.getResults() == null || raw.getResults().isEmpty()) {
                return null;
            }

            // Batch code → entity lookup (N+1 prevention)
            Set<String> codes = new HashSet<>();
            for (FintablesOrderbookResponseDto.OrderbookItem item : raw.getResults()) {
                if (item.getBb() != null && !item.getBb().isBlank()) codes.add(item.getBb());
                if (item.getSb() != null && !item.getSb().isBlank()) codes.add(item.getSb());
            }
            Map<String, AraciKurum> kurumMap = codes.isEmpty()
                    ? Collections.emptyMap()
                    : araciKurumRepository.findByCodeIn(codes)
                            .stream()
                            .collect(Collectors.toMap(AraciKurum::getCode, Function.identity()));

            // Enrich transactions
            List<OrderbookTransactionDto> transactions = raw.getResults().stream()
                    .map(item -> enrichTransaction(item, kurumMap))
                    .collect(Collectors.toList());

            return OrderbookResponseDto.builder()
                    .transactions(transactions)
                    .totalCount(transactions.size())
                    .build();

        } catch (Exception e) {
            log.error("[Orderbook] API'den veri alınırken hata: stockCode={}", stockCode, e);
            return null;
        }
    }

    /**
     * DB cache'den orderbook verisini okur.
     *
     * @param stockCode hisse kodu
     * @param date      cache tarihi
     * @return cache'deki veri (varsa)
     */
    private Optional<OrderbookResponseDto> readFromDbCache(String stockCode, LocalDate date) {
        return cacheRepository
                .findByStockCodeAndCacheDateAndDataType(stockCode, date, EnrichmentDataTypeEnum.ORDERBOOK)
                .map(cacheEntity -> {
                    try {
                        return objectMapper.readValue(cacheEntity.getJsonData(), OrderbookResponseDto.class);
                    } catch (JsonProcessingException e) {
                        log.error("[Orderbook] Cache JSON parse hata: stockCode={}, date={}", stockCode, date, e);
                        return null;
                    }
                });
    }

    /**
     * Ham orderbook item'ı zenginleştirilmiş DTO'ya dönüştürür.
     *
     * @param item     Fintables ham orderbook item
     * @param kurumMap kod → AraciKurum eşleme haritası
     * @return zenginleştirilmiş işlem DTO'su
     */
    private OrderbookTransactionDto enrichTransaction(FintablesOrderbookResponseDto.OrderbookItem item,
                                                       Map<String, AraciKurum> kurumMap) {
        AraciKurum buyer = kurumMap.get(item.getBb());
        AraciKurum seller = kurumMap.get(item.getSb());

        String time = Instant.ofEpochSecond(item.getT())
                .atZone(ISTANBUL_ZONE)
                .format(TIME_FORMAT);

        return OrderbookTransactionDto.builder()
                .time(time)
                .timestamp(item.getT())
                .price(item.getP())
                .lot((int) Math.round(item.getS()))
                .action(item.getA())
                .actionLabel("B".equals(item.getA()) ? "Alış" : "S".equals(item.getA()) ? "Satış" : item.getA())
                .buyerCode(item.getBb())
                .buyerShortTitle(buyer != null ? buyer.getShortTitle() : item.getBb())
                .buyerLogoUrl(buyer != null ? buyer.getLogoUrl() : null)
                .sellerCode(item.getSb())
                .sellerShortTitle(seller != null ? seller.getShortTitle() : item.getSb())
                .sellerLogoUrl(seller != null ? seller.getLogoUrl() : null)
                .build();
    }

    /**
     * In-memory cache'e veri ekler. MAX_CACHE_SIZE aşılırsa en eski entry temizlenir.
     *
     * @param stockCode hisse kodu
     * @param data      cache'lenecek veri
     */
    private void putCache(String stockCode, OrderbookResponseDto data) {
        if (cache.size() >= MAX_CACHE_SIZE) {
            cache.entrySet().stream()
                    .min(Comparator.comparingLong(e -> e.getValue().timestamp))
                    .ifPresent(oldest -> cache.remove(oldest.getKey()));
        }
        cache.put(stockCode, new CacheEntry(data, System.currentTimeMillis()));
    }

    /**
     * Boş orderbook response oluşturur.
     *
     * @return işlem listesi boş olan response
     */
    private OrderbookResponseDto emptyResponse() {
        return OrderbookResponseDto.builder()
                .transactions(List.of())
                .totalCount(0)
                .build();
    }

    /**
     * In-memory cache entry.
     */
    private static class CacheEntry {
        /** Cache'lenmis orderbook verisi. */
        final OrderbookResponseDto data;

        /** Cache'e yazilma zamani (epoch milisaniye). */
        final long timestamp;

        CacheEntry(OrderbookResponseDto data, long timestamp) {
            this.data = data;
            this.timestamp = timestamp;
        }

        boolean isExpired(long ttlMs) {
            return System.currentTimeMillis() - timestamp > ttlMs;
        }
    }
}
