package com.scyborsa.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.dto.fund.FundDetailDto;
import com.scyborsa.api.service.client.VelzonApiClient;
import com.scyborsa.api.dto.fund.FundDto;
import com.scyborsa.api.dto.fund.FundStatsDto;
import com.scyborsa.api.dto.fund.FundTimeSeriesDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * TEFAS fon verilerini api.velzon.tr uzerinden saglayan servis.
 *
 * <p>VelzonApiClient kullanarak TEFAS Fund API endpoint'lerini cagirir.
 * Fon listesi volatile cache ile saklanir (varsayilan TTL: 24 saat,
 * double-check locking). Startup'ta {@link #warmUpCache()} ile on-yukleme,
 * her gece 03:00'te {@link #scheduledCacheRefresh()} ile gunluk yenileme yapilir.</p>
 *
 * <p>api.velzon.tr response formati: {@code {success: true, data: [...], pagination: {...}}}.
 * {@code data} dizisi extract edilip {@link FundDto} listesine donusturulur.</p>
 *
 * @see VelzonApiClient
 * @see FundDto
 * @see FundStatsDto
 */
@Slf4j
@Service
public class FundService {

    /** Velzon API istemcisi. */
    private final VelzonApiClient velzonApiClient;

    /** JSON parse icin Jackson ObjectMapper. */
    private final ObjectMapper objectMapper;

    /** Cache: tum fonlar. */
    private volatile List<FundDto> cachedFunds;

    /** Cache: son guncelleme zamani (epoch millis). */
    private volatile long cacheTimestamp;

    /** Cache kilit nesnesi. */
    private final Object cacheLock = new Object();

    /** Cache TTL (saniye cinsinden, varsayilan 24 saat). TEFAS verileri gunluk guncellenir. */
    @Value("${fund.cache.ttl-seconds:86400}")
    private int cacheTtlSeconds;

    /**
     * Constructor injection ile bagimliliklari alir.
     *
     * @param velzonApiClient Velzon API istemcisi
     * @param objectMapper    JSON parse icin Jackson ObjectMapper
     */
    public FundService(VelzonApiClient velzonApiClient, ObjectMapper objectMapper) {
        this.velzonApiClient = velzonApiClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Uygulama basladiginda fon cache'ini doldurur.
     *
     * <p>Velzon API cold start nedeniyle ilk istek yavas olabilir.
     * Startup'ta async olarak cache doldurularak kullanici beklemesi onlenir.</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void warmUpCache() {
        log.info("[FUND] Startup cache warm-up basladi");
        try {
            List<FundDto> funds = getAllFunds();
            log.info("[FUND] Startup cache warm-up tamamlandi: {} fon", funds.size());
        } catch (Exception e) {
            log.warn("[FUND] Startup cache warm-up basarisiz: {}", e.getMessage());
        }
    }

    /**
     * Her gece 03:00'te fon cache'ini yeniler.
     *
     * <p>TEFAS verileri gunluk guncellenir (seans kapanisi sonrasi).
     * Gece 03:00'te taze veri cekilir, 24 saat boyunca cache'te tutulur.</p>
     */
    @Scheduled(cron = "0 0 3 * * *", zone = "Europe/Istanbul")
    public void scheduledCacheRefresh() {
        log.info("[FUND] Gunluk cache yenileme basladi (03:00 scheduled)");
        try {
            // Cache'i invalidate et — force refresh
            this.cacheTimestamp = 0;
            List<FundDto> funds = getAllFunds();
            log.info("[FUND] Gunluk cache yenileme tamamlandi: {} fon", funds.size());
        } catch (Exception e) {
            log.warn("[FUND] Gunluk cache yenileme basarisiz: {}", e.getMessage());
        }
    }

    /**
     * Tum aktif fonlari dondurur.
     *
     * <p>api.velzon.tr'deki {@code GET /api/funds?page=0&size=2500&isActive=true}
     * endpoint'ini cagirir. Sonuclar volatile cache ile saklanir (TTL: 24 saat).</p>
     *
     * @return fon listesi; hata durumunda bos liste
     */
    public List<FundDto> getAllFunds() {
        long now = System.currentTimeMillis();
        if (cachedFunds != null && (now - cacheTimestamp) < (long) cacheTtlSeconds * 1000) {
            return cachedFunds;
        }

        synchronized (cacheLock) {
            now = System.currentTimeMillis();
            if (cachedFunds != null && (now - cacheTimestamp) < (long) cacheTtlSeconds * 1000) {
                return cachedFunds;
            }

            log.info("[FUND] Tum fon verileri Velzon API'den cekiliyor");
            try {
                Map<String, Object> response = velzonApiClient.get(
                        "/api/funds?page=0&size=2500&isActive=true",
                        new TypeReference<Map<String, Object>>() {}
                );

                Object success = response.get("success");
                if (!Boolean.TRUE.equals(success)) {
                    log.warn("[FUND] API response success=false");
                    return cachedFunds != null ? cachedFunds : List.of();
                }

                List<FundDto> funds = extractDataList(response);

                if (funds.isEmpty()) {
                    log.warn("[FUND] Velzon API bos fon listesi dondurdu");
                    return cachedFunds != null ? cachedFunds : List.of();
                }

                this.cachedFunds = Collections.unmodifiableList(funds);
                this.cacheTimestamp = System.currentTimeMillis();

                log.info("[FUND] {} fon basariyla yuklendi", funds.size());
                return cachedFunds;

            } catch (Exception e) {
                log.error("[FUND] Fon verileri alinamadi: {}", e.getMessage(), e);
                return cachedFunds != null ? cachedFunds : List.of();
            }
        }
    }

    /**
     * Fon araması yapar.
     *
     * <p>api.velzon.tr'deki {@code GET /api/funds/search?query=X&limit=20}
     * endpoint'ini cagirir.</p>
     *
     * @param query arama terimi (min 2 karakter)
     * @param limit maksimum sonuc sayisi
     * @return eslesen fon listesi; hata durumunda bos liste
     */
    public List<FundDto> searchFunds(String query, int limit) {
        if (query == null || query.trim().length() < 2) {
            return List.of();
        }
        String sanitized = query.replaceAll("[\\r\\n]", "").trim();
        log.info("[FUND] Fon araniyor [query={}, limit={}]", sanitized, limit);
        try {
            String encodedQuery = URLEncoder.encode(sanitized, StandardCharsets.UTF_8);
            Map<String, Object> response = velzonApiClient.get(
                    "/api/funds/search?query=" + encodedQuery + "&limit=" + limit,
                    new TypeReference<Map<String, Object>>() {}
            );
            return extractDataList(response);
        } catch (Exception e) {
            log.error("[FUND] Fon arama basarisiz: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Populer fonlari getirir.
     *
     * <p>api.velzon.tr'deki {@code GET /api/funds/popular?limit=50}
     * endpoint'ini cagirir.</p>
     *
     * @param limit maksimum sonuc sayisi
     * @return populer fon listesi; hata durumunda bos liste
     */
    public List<FundDto> getPopularFunds(int limit) {
        log.info("[FUND] Populer fonlar isteniyor [limit={}]", limit);
        try {
            Map<String, Object> response = velzonApiClient.get(
                    "/api/funds/popular?limit=" + limit,
                    new TypeReference<Map<String, Object>>() {}
            );
            return extractDataList(response);
        } catch (Exception e) {
            log.error("[FUND] Populer fonlar alinamadi: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Fon istatistiklerini getirir.
     *
     * <p>api.velzon.tr'deki {@code GET /api/funds/stats}
     * endpoint'ini cagirir.</p>
     *
     * @return fon istatistikleri; hata durumunda bos istatistik
     */
    public FundStatsDto getFundStats() {
        log.info("[FUND] Fon istatistikleri isteniyor");
        try {
            Map<String, Object> response = velzonApiClient.get(
                    "/api/funds/stats",
                    new TypeReference<Map<String, Object>>() {}
            );

            Object rawData = response.get("data");
            if (rawData instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> dataMap = (Map<String, Object>) rawData;
                return objectMapper.convertValue(dataMap, FundStatsDto.class);
            }

            log.warn("[FUND] Stats data beklenmeyen formatta: {}",
                    rawData != null ? rawData.getClass().getSimpleName() : "null");
            return emptyStats();

        } catch (Exception e) {
            log.error("[FUND] Fon istatistikleri alinamadi: {}", e.getMessage());
            return emptyStats();
        }
    }

    /**
     * Tek fon bilgisi getirir (cache'den filtreleme).
     *
     * @param tefasCode TEFAS fon kodu (örn: "AAJ")
     * @return Bulunan fon bilgisi veya null
     */
    public FundDto getFundByCode(String tefasCode) {
        List<FundDto> funds = getAllFunds();
        return funds.stream()
                .filter(f -> tefasCode.equalsIgnoreCase(f.getTefasCode()))
                .findFirst()
                .orElse(null);
    }

    /**
     * api.velzon.tr wrapper response'undan data listesini extract eder.
     *
     * <p>Response formati: {@code {success: true, data: [...], pagination: {...}}}.
     * {@code data} dizisi {@link FundDto} listesine donusturulur.</p>
     *
     * @param response API response map'i
     * @return fon listesi; parse hatasinda bos liste
     */
    @SuppressWarnings("unchecked")
    private List<FundDto> extractDataList(Map<String, Object> response) {
        if (response == null) {
            log.warn("[FUND] API response null");
            return List.of();
        }
        Object rawData = response.get("data");
        if (!(rawData instanceof List)) {
            log.warn("[FUND] Data alani beklenmeyen formatta: {}",
                    rawData != null ? rawData.getClass().getSimpleName() : "null");
            return List.of();
        }

        List<Map<String, Object>> dataList = (List<Map<String, Object>>) rawData;
        List<FundDto> result = new ArrayList<>();
        int parseErrors = 0;
        for (Map<String, Object> item : dataList) {
            try {
                FundDto dto = objectMapper.convertValue(item, FundDto.class);
                // api.velzon.tr participation alanini doldurmuyor — category'den turet
                if (!dto.isParticipation() && dto.getFundCategory() != null
                        && dto.getFundCategory().contains("Katılım")) {
                    dto.setParticipation(true);
                }
                result.add(dto);
            } catch (Exception e) {
                parseErrors++;
                log.debug("[FUND] Fon parse hatasi: {}", e.getMessage());
            }
        }
        if (parseErrors > 0) {
            log.warn("[FUND] {} fon parse edilemedi (toplam: {})", parseErrors, dataList.size());
        }
        return result;
    }

    /**
     * Fon detay bilgilerini getirir.
     *
     * <p>api.velzon.tr'deki {@code GET /api/funds/{code}/detail}
     * endpoint'ini cagirir. Varlik dagilimi, pozisyonlar, benzer fonlar,
     * kategori siralamalari gibi kapsamli bilgileri dondurur.</p>
     *
     * @param code TEFAS fon kodu (orn. "AAK")
     * @return fon detay bilgileri; hata durumunda null
     */
    public FundDetailDto getFundDetail(String code) {
        if (!isValidFundCode(code)) return null;
        log.info("[FUND] Fon detay isteniyor [code={}]", code);
        try {
            Map<String, Object> response = velzonApiClient.get(
                    "/api/funds/" + code + "/detail",
                    new TypeReference<Map<String, Object>>() {}
            );
            return extractDataObject(response, FundDetailDto.class);
        } catch (Exception e) {
            log.error("[FUND] Fon detay alinamadi [code={}]: {}", code, e.getMessage());
            return null;
        }
    }

    /**
     * Fon nakit akisi (cashflow) zaman serisi verilerini getirir.
     *
     * <p>api.velzon.tr'deki {@code GET /api/funds/{code}/cashflow}
     * endpoint'ini cagirir.</p>
     *
     * @param code TEFAS fon kodu (orn. "AAK")
     * @return nakit akisi zaman serisi listesi; hata durumunda bos liste
     */
    public List<FundTimeSeriesDto> getFundCashflow(String code) {
        if (!isValidFundCode(code)) return List.of();
        log.info("[FUND] Fon nakit akisi isteniyor [code={}]", code);
        try {
            Map<String, Object> response = velzonApiClient.get(
                    "/api/funds/" + code + "/cashflow",
                    new TypeReference<Map<String, Object>>() {}
            );
            return extractTimeSeriesList(response);
        } catch (Exception e) {
            log.error("[FUND] Fon nakit akisi alinamadi [code={}]: {}", code, e.getMessage());
            return List.of();
        }
    }

    /**
     * Fon yatirimci sayisi zaman serisi verilerini getirir.
     *
     * <p>api.velzon.tr'deki {@code GET /api/funds/{code}/investors}
     * endpoint'ini cagirir.</p>
     *
     * @param code TEFAS fon kodu (orn. "AAK")
     * @return yatirimci sayisi zaman serisi listesi; hata durumunda bos liste
     */
    public List<FundTimeSeriesDto> getFundInvestors(String code) {
        if (!isValidFundCode(code)) return List.of();
        log.info("[FUND] Fon yatirimci verisi isteniyor [code={}]", code);
        try {
            Map<String, Object> response = velzonApiClient.get(
                    "/api/funds/" + code + "/investors",
                    new TypeReference<Map<String, Object>>() {}
            );
            return extractTimeSeriesList(response);
        } catch (Exception e) {
            log.error("[FUND] Fon yatirimci verisi alinamadi [code={}]: {}", code, e.getMessage());
            return List.of();
        }
    }

    /**
     * Fon PDF raporunu indirir.
     *
     * <p>api.velzon.tr'deki {@code GET /api/funds/{code}/pdf}
     * endpoint'inden binary PDF icerigini dondurur.</p>
     *
     * @param code TEFAS fon kodu (orn. "AAK")
     * @return PDF iceriginin byte dizisi; hata durumunda null
     */
    public byte[] getFundPdf(String code) {
        if (!isValidFundCode(code)) return null;
        log.info("[FUND] Fon PDF raporu isteniyor [code={}]", code);
        try {
            return velzonApiClient.getBytes("/api/funds/" + code + "/pdf");
        } catch (Exception e) {
            log.error("[FUND] Fon PDF raporu alinamadi [code={}]: {}", code, e.getMessage());
            return null;
        }
    }

    /**
     * Fon fiyat gecmisini CSV olarak indirir.
     *
     * <p>api.velzon.tr'deki {@code GET /api/funds/{code}/history/csv}
     * endpoint'inden CSV icerigini dondurur.</p>
     *
     * @param code TEFAS fon kodu (orn. "AAK")
     * @return CSV iceriginin byte dizisi; hata durumunda null
     */
    public byte[] getFundCsv(String code) {
        if (!isValidFundCode(code)) return null;
        log.info("[FUND] Fon CSV gecmisi isteniyor [code={}]", code);
        try {
            return velzonApiClient.getBytes("/api/funds/" + code + "/history/csv");
        } catch (Exception e) {
            log.error("[FUND] Fon CSV gecmisi alinamadi [code={}]: {}", code, e.getMessage());
            return null;
        }
    }

    /**
     * Fon kodunun gecerli olup olmadigini kontrol eder.
     *
     * <p>Null, bos, 10 karakterden uzun veya alfanumerik olmayan kodlari reddeder.
     * Servis katmaninda savunma amacli kullanilir.</p>
     *
     * @param code kontrol edilecek fon kodu
     * @return gecerli ise true, degilse false
     */
    private boolean isValidFundCode(String code) {
        if (code == null || code.isEmpty() || code.length() > 10 || !code.matches("[a-zA-Z0-9]+")) {
            log.warn("[FUND] Gecersiz fon kodu: {}", code != null ? code.replaceAll("[\\r\\n]", "") : "null");
            return false;
        }
        return true;
    }

    /**
     * api.velzon.tr wrapper response'undan data nesnesini extract edip belirtilen tipe donusturur.
     *
     * <p>Response formati: {@code {success: true, data: {...}}}.
     * {@code data} Map olarak alinir ve Jackson {@code convertValue} ile hedef tipe donusturulur.</p>
     *
     * @param <T>      hedef tip
     * @param response API response map'i
     * @param clazz    hedef sinif
     * @return donusturulmus nesne; format hatasi durumunda null
     */
    @SuppressWarnings("unchecked")
    private <T> T extractDataObject(Map<String, Object> response, Class<T> clazz) {
        if (response == null) {
            log.warn("[FUND] API response null");
            return null;
        }
        Object rawData = response.get("data");
        if (rawData instanceof Map) {
            Map<String, Object> dataMap = (Map<String, Object>) rawData;
            return objectMapper.convertValue(dataMap, clazz);
        }
        log.warn("[FUND] Data alani beklenmeyen formatta: {}",
                rawData != null ? rawData.getClass().getSimpleName() : "null");
        return null;
    }

    /**
     * api.velzon.tr wrapper response'undan zaman serisi listesini extract eder.
     *
     * <p>Response formati: {@code {success: true, data: [...]}}.
     * {@code data} dizisi {@link FundTimeSeriesDto} listesine donusturulur.</p>
     *
     * @param response API response map'i
     * @return zaman serisi listesi; parse hatasinda bos liste
     */
    @SuppressWarnings("unchecked")
    private List<FundTimeSeriesDto> extractTimeSeriesList(Map<String, Object> response) {
        if (response == null) {
            log.warn("[FUND] API response null");
            return List.of();
        }
        Object rawData = response.get("data");
        if (!(rawData instanceof List)) {
            log.warn("[FUND] Data alani beklenmeyen formatta: {}",
                    rawData != null ? rawData.getClass().getSimpleName() : "null");
            return List.of();
        }

        List<Map<String, Object>> dataList = (List<Map<String, Object>>) rawData;
        List<FundTimeSeriesDto> result = new ArrayList<>();
        int parseErrors = 0;
        for (Map<String, Object> item : dataList) {
            try {
                FundTimeSeriesDto dto = objectMapper.convertValue(item, FundTimeSeriesDto.class);
                result.add(dto);
            } catch (Exception e) {
                parseErrors++;
                log.debug("[FUND] Zaman serisi parse hatasi: {}", e.getMessage());
            }
        }
        if (parseErrors > 0) {
            log.warn("[FUND] {} zaman serisi noktasi parse edilemedi (toplam: {})", parseErrors, dataList.size());
        }
        return result;
    }

    /**
     * Bos fon istatistikleri olusturur.
     *
     * @return tum degerleri sifir olan FundStatsDto
     */
    private FundStatsDto emptyStats() {
        return FundStatsDto.builder()
                .totalActiveFunds(0)
                .totalInvestors(0)
                .totalPortfolioSize(0)
                .yatFundCount(0)
                .emkFundCount(0)
                .byfFundCount(0)
                .build();
    }

}
