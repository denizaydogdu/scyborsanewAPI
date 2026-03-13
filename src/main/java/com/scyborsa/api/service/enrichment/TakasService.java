package com.scyborsa.api.service.enrichment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.dto.enrichment.FintablesTakasResponseDto;
import com.scyborsa.api.dto.enrichment.TakasResponseDto;
import com.scyborsa.api.dto.enrichment.TakasResponseDto.TakasCustodianEnrichedDto;
import com.scyborsa.api.enums.EnrichmentDataTypeEnum;
import com.scyborsa.api.enums.SessionHolidays;
import com.scyborsa.api.model.AraciKurum;
import com.scyborsa.api.model.EnrichmentCache;
import com.scyborsa.api.repository.AraciKurumRepository;
import com.scyborsa.api.repository.EnrichmentCacheRepository;
import com.scyborsa.api.service.FintablesApiClient;
import com.scyborsa.api.utils.AkdTakasTimeResolver;
import com.scyborsa.api.utils.AkdTakasTimeResolver.ReadStrategy;
import com.scyborsa.api.utils.BistTradingCalendar;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Takas (Saklama Dağılımı) iş mantığı servisi.
 *
 * <p>Fintables API'den ham saklama kuruluşu verisini alır, DB'deki
 * {@link AraciKurum} entity'leri ile zenginleştirir ve
 * API response olarak döner.</p>
 *
 * <p>Zaman bazlı okuma stratejisi kullanır: seans içi canlı API, seans dışı DB cache.</p>
 *
 * <p>Mevcut {@link TakasApiService} interface'ini implement ETMEZ —
 * farklı return tipi (enriched DTO vs simple DTO). Stub, Telegram
 * formatter tarafından kullanılmaya devam eder.</p>
 *
 * @see AkdTakasTimeResolver
 * @see EnrichmentCacheRepository
 * @see FintablesApiClient#getTakas(String, String)
 * @see AraciKurumRepository#findByCodeIn(java.util.Collection)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TakasService {

    /** Istanbul saat dilimi. */
    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");

    /** Turkce tarih formatlayici (orn: "5 Mart 2025"). */
    private static final DateTimeFormatter TR_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("d MMMM yyyy", new Locale("tr", "TR"));

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

    /**
     * Hisse bazlı Takas (saklama) dağılımını zaman bazlı strateji ile getirir.
     *
     * <p>Günün saatine göre veriyi DB'den veya API'den okur.
     * Seans içi canlı API, seans dışı DB cache tercih edilir.</p>
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return zenginleştirilmiş Takas dağılımı
     */
    public TakasResponseDto getTakasDistribution(String stockCode) {
        try {
            LocalDate today = LocalDate.now(ISTANBUL_ZONE);
            LocalTime now = LocalTime.now(ISTANBUL_ZONE);
            ReadStrategy strategy = timeResolver.resolve(today, now);

            log.debug("[Takas] stockCode={}, strategy={}", stockCode, strategy);

            switch (strategy) {
                case DB_PREVIOUS_DAY:
                    LocalDate prevDay = BistTradingCalendar.getPreviousTradingDay(today);
                    return readFromCache(stockCode, prevDay)
                            .orElse(emptyResponse(prevDay));

                case LIVE_API: {
                    TakasResponseDto data = fetchAndEnrichFromApi(stockCode);
                    return data != null ? data : emptyResponse(today);
                }

                case DB_FIRST_THEN_API:
                    return readFromCache(stockCode, today)
                            .orElseGet(() -> {
                                TakasResponseDto data = fetchAndEnrichFromApi(stockCode);
                                if (data != null && data.getCustodians() != null && !data.getCustodians().isEmpty()) {
                                    saveToCache(stockCode, today, data);
                                }
                                return data != null ? data : emptyResponse(today);
                            });

                case DB_ONLY:
                    return readFromCache(stockCode, today)
                            .orElse(emptyResponse(today));

                default:
                    return emptyResponse(today);
            }
        } catch (Exception e) {
            log.error("[Takas] Veri alınırken hata: stockCode={}", stockCode, e);
            return emptyResponse(null);
        }
    }

    /**
     * Fintables API'den Takas verisini çeker ve zenginleştirir.
     *
     * <p>Sync job ve DB_FIRST_THEN_API stratejisi tarafından kullanılır.</p>
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return zenginleştirilmiş Takas dağılımı, hata durumunda {@code null}
     */
    public TakasResponseDto fetchAndEnrichFromApi(String stockCode) {
        try {
            String targetDate = resolveTargetDate();
            FintablesTakasResponseDto raw = fintablesApiClient.getTakas(stockCode, targetDate);

            if (raw == null || raw.getResults() == null || raw.getResults().isEmpty()) {
                return emptyResponse(LocalDate.parse(targetDate));
            }

            // Batch code → entity lookup (N+1 prevention)
            Set<String> codes = raw.getResults().stream()
                    .map(FintablesTakasResponseDto.TakasItem::getCustodian)
                    .collect(Collectors.toSet());
            Map<String, AraciKurum> kurumMap = araciKurumRepository.findByCodeIn(codes)
                    .stream()
                    .collect(Collectors.toMap(AraciKurum::getCode, Function.identity()));

            List<TakasCustodianEnrichedDto> custodians = raw.getResults().stream()
                    .map(item -> toEnrichedDto(item, kurumMap))
                    .collect(Collectors.toList());

            LocalDate dataDate = LocalDate.parse(targetDate);
            return TakasResponseDto.builder()
                    .custodians(custodians)
                    .dataDate(targetDate)
                    .formattedDataDate(dataDate.format(TR_DATE_FORMATTER))
                    .build();

        } catch (Exception e) {
            log.error("[Takas] API'den veri alınırken hata: stockCode={}", stockCode, e);
            return null;
        }
    }

    /**
     * DB cache'den Takas verisini okur.
     *
     * @param stockCode hisse kodu
     * @param date      cache tarihi
     * @return cache'deki veri (varsa)
     */
    private Optional<TakasResponseDto> readFromCache(String stockCode, LocalDate date) {
        return cacheRepository
                .findByStockCodeAndCacheDateAndDataType(stockCode, date, EnrichmentDataTypeEnum.TAKAS)
                .map(cache -> {
                    try {
                        TakasResponseDto dto = objectMapper.readValue(cache.getJsonData(), TakasResponseDto.class);
                        // Cache'den okunan veriye tarih bilgisi ekle (JSON'da yoksa)
                        if (dto.getDataDate() == null) {
                            dto.setDataDate(date.format(DateTimeFormatter.ISO_LOCAL_DATE));
                            dto.setFormattedDataDate(date.format(TR_DATE_FORMATTER));
                        }
                        return dto;
                    } catch (JsonProcessingException e) {
                        log.error("[Takas] Cache JSON parse hata: stockCode={}, date={}", stockCode, date, e);
                        return null;
                    }
                });
    }

    /**
     * Zenginleştirilmiş Takas verisini DB cache'e kaydeder.
     *
     * @param stockCode hisse kodu
     * @param date      cache tarihi
     * @param data      kaydedilecek veri
     */
    private void saveToCache(String stockCode, LocalDate date, TakasResponseDto data) {
        try {
            if (!cacheRepository.existsByStockCodeAndCacheDateAndDataType(
                    stockCode, date, EnrichmentDataTypeEnum.TAKAS)) {
                String json = objectMapper.writeValueAsString(data);
                cacheRepository.save(EnrichmentCache.builder()
                        .stockCode(stockCode)
                        .cacheDate(date)
                        .dataType(EnrichmentDataTypeEnum.TAKAS)
                        .jsonData(json)
                        .build());
                log.debug("[Takas] Cache'e kaydedildi: stockCode={}, date={}", stockCode, date);
            }
        } catch (JsonProcessingException e) {
            log.error("[Takas] Cache JSON serialize hata: stockCode={}", stockCode, e);
        } catch (Exception e) {
            log.warn("[Takas] Cache kaydetme hatası (concurrent write?): stockCode={}, date={}",
                    stockCode, date, e);
        }
    }

    /**
     * Ham Takas item'ını zenginleştirilmiş DTO'ya dönüştürür.
     *
     * @param item     Fintables ham item
     * @param kurumMap kod → AraciKurum eşleme haritası
     * @return zenginleştirilmiş DTO
     */
    private TakasCustodianEnrichedDto toEnrichedDto(FintablesTakasResponseDto.TakasItem item,
                                                     Map<String, AraciKurum> kurumMap) {
        AraciKurum kurum = kurumMap.get(item.getCustodian());
        double yuzde = item.getPercentage() * 100;

        return TakasCustodianEnrichedDto.builder()
                .code(item.getCustodian())
                .title(kurum != null ? kurum.getTitle() : item.getCustodian())
                .shortTitle(kurum != null ? kurum.getShortTitle() : item.getCustodian())
                .logoUrl(kurum != null ? kurum.getLogoUrl() : null)
                .deger(item.getValue())
                .formattedDeger(formatValue(item.getValue()))
                .yuzde(yuzde)
                .build();
    }

    /**
     * TL değerini okunabilir formata dönüştürür.
     *
     * @param value TL cinsinden değer
     * @return formatlanmış string (ör: "1.87 Milyar TL")
     */
    private String formatValue(double value) {
        double abs = Math.abs(value);
        if (abs >= 1_000_000_000) {
            return String.format("%.2f Milyar TL", value / 1_000_000_000.0);
        } else if (abs >= 1_000_000) {
            return String.format("%.2f Milyon TL", value / 1_000_000.0);
        } else if (abs >= 1_000) {
            return String.format("%.2f Bin TL", value / 1_000.0);
        }
        return String.format("%.2f TL", value);
    }

    /**
     * Fintables API'ye gönderilecek hedef tarihi belirler.
     *
     * <p>Bugün işlem günüyse (yarım gün dahil) bugünün tarihini,
     * tatil veya hafta sonu ise en son işlem gününün tarihini döner.</p>
     *
     * @return yyyy-MM-dd formatında hedef tarih
     */
    private String resolveTargetDate() {
        LocalDate today = LocalDate.now(ISTANBUL_ZONE);
        if (SessionHolidays.isNonTradingDay(today)) {
            LocalDate prevDay = BistTradingCalendar.getPreviousTradingDay(today);
            log.debug("[Takas] Bugün işlem günü değil ({}), önceki işlem günü kullanılıyor: {}",
                    today, prevDay);
            return prevDay.format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        return today.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /**
     * Boş Takas response oluşturur.
     *
     * @param date verinin ait olduğu tarih (null olabilir)
     * @return boş custodians listeli response
     */
    private TakasResponseDto emptyResponse(LocalDate date) {
        TakasResponseDto.TakasResponseDtoBuilder builder = TakasResponseDto.builder()
                .custodians(List.of());
        if (date != null) {
            builder.dataDate(date.format(DateTimeFormatter.ISO_LOCAL_DATE))
                    .formattedDataDate(date.format(TR_DATE_FORMATTER));
        }
        return builder.build();
    }
}
