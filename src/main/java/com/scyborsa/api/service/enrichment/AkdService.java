package com.scyborsa.api.service.enrichment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.dto.enrichment.AkdBrokerDto;
import com.scyborsa.api.dto.enrichment.AkdResponseDto;
import com.scyborsa.api.dto.enrichment.FintablesAkdResponseDto;
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
 * AKD (Aracı Kurum Dağılımı) iş mantığı servisi.
 *
 * <p>Fintables API'den ham veriyi alır, DB'deki {@link AraciKurum} entity'leri ile
 * zenginleştirir ve alıcı/satıcı/toplam olarak ayrıştırır.</p>
 *
 * <p>Zaman bazlı okuma stratejisi kullanır: seans içi canlı API, seans dışı DB cache.</p>
 *
 * @see AkdTakasTimeResolver
 * @see EnrichmentCacheRepository
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AkdService {

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
     * Hisse bazlı AKD dağılımını zaman bazlı strateji ile getirir.
     *
     * <p>Günün saatine göre veriyi DB'den veya API'den okur.
     * Seans içi canlı API, seans dışı DB cache tercih edilir.</p>
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return zenginleştirilmiş AKD dağılımı (alıcılar, satıcılar, toplam)
     */
    public AkdResponseDto getAkdDistribution(String stockCode) {
        try {
            LocalDate today = LocalDate.now(ISTANBUL_ZONE);
            LocalTime now = LocalTime.now(ISTANBUL_ZONE);
            ReadStrategy strategy = timeResolver.resolve(today, now);

            log.debug("[AKD] stockCode={}, strategy={}", stockCode, strategy);

            switch (strategy) {
                case DB_PREVIOUS_DAY:
                    LocalDate prevDay = BistTradingCalendar.getPreviousTradingDay(today);
                    return readFromCache(stockCode, prevDay)
                            .orElse(emptyResponse(prevDay));

                case LIVE_API: {
                    AkdResponseDto data = fetchAndEnrichFromApi(stockCode);
                    return data != null ? data : emptyResponse(today);
                }

                case DB_FIRST_THEN_API:
                    return readFromCache(stockCode, today)
                            .orElseGet(() -> {
                                AkdResponseDto data = fetchAndEnrichFromApi(stockCode);
                                if (data != null && data.getAlicilar() != null && !data.getAlicilar().isEmpty()) {
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
            log.error("[AKD] Veri alınırken hata: stockCode={}", stockCode, e);
            return emptyResponse(null);
        }
    }

    /**
     * Fintables API'den AKD verisini çeker ve zenginleştirir.
     *
     * <p>Sync job ve DB_FIRST_THEN_API stratejisi tarafından kullanılır.</p>
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return zenginleştirilmiş AKD dağılımı, hata durumunda {@code null}
     */
    public AkdResponseDto fetchAndEnrichFromApi(String stockCode) {
        try {
            String targetDate = resolveTargetDate();
            FintablesAkdResponseDto raw = fintablesApiClient.getAkd(stockCode, targetDate, targetDate);

            if (raw == null || raw.getResults() == null || raw.getResults().isEmpty()) {
                return emptyResponse(LocalDate.parse(targetDate));
            }

            // Batch code → entity lookup (N+1 prevention)
            Set<String> codes = raw.getResults().stream()
                    .map(FintablesAkdResponseDto.AkdItem::getBrokerage)
                    .collect(Collectors.toSet());
            Map<String, AraciKurum> kurumMap = araciKurumRepository.findByCodeIn(codes)
                    .stream()
                    .collect(Collectors.toMap(AraciKurum::getCode, Function.identity()));

            // Alıcılar: net.size > 0, sorted desc
            List<AkdBrokerDto> alicilar = raw.getResults().stream()
                    .filter(i -> i.getNet() != null && i.getNet().getSize() > 0)
                    .sorted(Comparator.comparingLong((FintablesAkdResponseDto.AkdItem i) -> i.getNet().getSize()).reversed())
                    .map(i -> toDto(i, i.getNet(), kurumMap))
                    .collect(Collectors.toCollection(ArrayList::new));

            // Satıcılar: net.size < 0, sorted desc by abs
            List<AkdBrokerDto> saticilar = raw.getResults().stream()
                    .filter(i -> i.getNet() != null && i.getNet().getSize() < 0)
                    .sorted(Comparator.comparingLong((FintablesAkdResponseDto.AkdItem i) -> i.getNet().getSize()))
                    .map(i -> toDto(i, i.getNet(), kurumMap))
                    .collect(Collectors.toCollection(ArrayList::new));
            // Satıcılar: adet'i pozitif yap (abs)
            saticilar.forEach(s -> s.setAdet(Math.abs(s.getAdet())));

            // Toplam: tümü, sorted desc by total.size
            List<AkdBrokerDto> toplam = raw.getResults().stream()
                    .filter(i -> i.getTotal() != null)
                    .sorted(Comparator.comparingLong((FintablesAkdResponseDto.AkdItem i) -> i.getTotal().getSize()).reversed())
                    .map(i -> toDto(i, i.getTotal(), kurumMap))
                    .collect(Collectors.toCollection(ArrayList::new));
            appendDiger(toplam);

            LocalDate dataDate = LocalDate.parse(targetDate);
            return AkdResponseDto.builder()
                    .alicilar(alicilar)
                    .saticilar(saticilar)
                    .toplam(toplam)
                    .dataDate(targetDate)
                    .formattedDataDate(dataDate.format(TR_DATE_FORMATTER))
                    .build();

        } catch (Exception e) {
            log.error("[AKD] API'den veri alınırken hata: stockCode={}", stockCode, e);
            return null;
        }
    }

    /**
     * DB cache'den AKD verisini okur.
     *
     * @param stockCode hisse kodu
     * @param date      cache tarihi
     * @return cache'deki veri (varsa)
     */
    private Optional<AkdResponseDto> readFromCache(String stockCode, LocalDate date) {
        return cacheRepository
                .findByStockCodeAndCacheDateAndDataType(stockCode, date, EnrichmentDataTypeEnum.AKD)
                .map(cache -> {
                    try {
                        AkdResponseDto dto = objectMapper.readValue(cache.getJsonData(), AkdResponseDto.class);
                        // Cache'den okunan veriye tarih bilgisi ekle (JSON'da yoksa)
                        if (dto.getDataDate() == null) {
                            dto.setDataDate(date.format(DateTimeFormatter.ISO_LOCAL_DATE));
                            dto.setFormattedDataDate(date.format(TR_DATE_FORMATTER));
                        }
                        return dto;
                    } catch (JsonProcessingException e) {
                        log.error("[AKD] Cache JSON parse hata: stockCode={}, date={}", stockCode, date, e);
                        return null;
                    }
                });
    }

    /**
     * Zenginleştirilmiş AKD verisini DB cache'e kaydeder.
     *
     * @param stockCode hisse kodu
     * @param date      cache tarihi
     * @param data      kaydedilecek veri
     */
    private void saveToCache(String stockCode, LocalDate date, AkdResponseDto data) {
        try {
            if (!cacheRepository.existsByStockCodeAndCacheDateAndDataType(
                    stockCode, date, EnrichmentDataTypeEnum.AKD)) {
                String json = objectMapper.writeValueAsString(data);
                cacheRepository.save(EnrichmentCache.builder()
                        .stockCode(stockCode)
                        .cacheDate(date)
                        .dataType(EnrichmentDataTypeEnum.AKD)
                        .jsonData(json)
                        .build());
                log.debug("[AKD] Cache'e kaydedildi: stockCode={}, date={}", stockCode, date);
            }
        } catch (JsonProcessingException e) {
            log.error("[AKD] Cache JSON serialize hata: stockCode={}", stockCode, e);
        } catch (Exception e) {
            log.warn("[AKD] Cache kaydetme hatası (concurrent write?): stockCode={}, date={}",
                    stockCode, date, e);
        }
    }

    /**
     * Ham AKD item ve metrik verisini zenginleştirilmiş DTO'ya dönüştürür.
     *
     * @param item     Fintables AKD item
     * @param metric   kullanılacak metrik (net veya total)
     * @param kurumMap kod → AraciKurum eşleme haritası
     * @return zenginleştirilmiş broker DTO
     */
    private AkdBrokerDto toDto(FintablesAkdResponseDto.AkdItem item,
                               FintablesAkdResponseDto.AkdMetric metric,
                               Map<String, AraciKurum> kurumMap) {
        AraciKurum kurum = kurumMap.get(item.getBrokerage());
        return AkdBrokerDto.builder()
                .code(item.getBrokerage())
                .title(kurum != null ? kurum.getTitle() : item.getBrokerage())
                .shortTitle(kurum != null ? kurum.getShortTitle() : item.getBrokerage())
                .logoUrl(kurum != null ? kurum.getLogoUrl() : null)
                .adet(metric.getSize())
                .yuzde(metric.getPercentage() * 100)
                .maliyet(metric.getCost())
                .build();
    }

    /**
     * "Diğer" satırını listeye ekler.
     * Toplam yüzde 100'den küçükse kalan yüzdelik "Diğer" olarak eklenir.
     *
     * @param list kurum listesi
     */
    private void appendDiger(List<AkdBrokerDto> list) {
        if (list.isEmpty()) return;
        double toplamYuzde = list.stream().mapToDouble(AkdBrokerDto::getYuzde).sum();
        double kalanYuzde = 100.0 - toplamYuzde;
        if (kalanYuzde > 0.01) {
            list.add(AkdBrokerDto.builder()
                    .code("DIGER")
                    .title("Diğer")
                    .shortTitle("Diğer")
                    .adet(0)
                    .yuzde(kalanYuzde)
                    .maliyet(0)
                    .build());
        }
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
            log.debug("[AKD] Bugün işlem günü değil ({}), önceki işlem günü kullanılıyor: {}",
                    today, prevDay);
            return prevDay.format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        return today.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /**
     * Boş AKD response oluşturur.
     *
     * @param date verinin ait olduğu tarih (null olabilir)
     * @return tüm listeleri boş olan response
     */
    private AkdResponseDto emptyResponse(LocalDate date) {
        AkdResponseDto.AkdResponseDtoBuilder builder = AkdResponseDto.builder()
                .alicilar(List.of())
                .saticilar(List.of())
                .toplam(List.of());
        if (date != null) {
            builder.dataDate(date.format(DateTimeFormatter.ISO_LOCAL_DATE))
                    .formattedDataDate(date.format(TR_DATE_FORMATTER));
        }
        return builder.build();
    }
}
