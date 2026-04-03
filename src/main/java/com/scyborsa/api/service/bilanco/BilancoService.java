package com.scyborsa.api.service.bilanco;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.dto.bilanco.*;
import com.scyborsa.api.dto.fintables.FinansalTabloDto;
import com.scyborsa.api.service.KatilimEndeksiService;
import com.scyborsa.api.service.client.GateVelzonApiClient;
import com.scyborsa.api.service.enrichment.FinansalTabloService;
import com.scyborsa.api.utils.BistCacheUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bilanco veri servisi.
 *
 * <p>Gate Velzon API'sinden bilanco, gelir tablosu, nakit akim ve rasyo verilerini ceker,
 * adaptive TTL ile cache'ler ve istemcilere sunar.</p>
 *
 * <h3>Cache Stratejisi:</h3>
 * <ul>
 *   <li><b>Son raporlar listesi:</b> Seans ici 5 dakika, seans disi 1 saat
 *       ({@link BistCacheUtils#getAdaptiveTTL(long, long)})</li>
 *   <li><b>Bireysel raporlar:</b> Seans ici 10 dakika, seans disi 2 saat
 *       (ConcurrentHashMap + CachedItem pattern)</li>
 *   <li><b>Rasyo verileri:</b> Seans ici 10 dakika, seans disi 2 saat</li>
 * </ul>
 *
 * <p>Hata durumunda mevcut cache korunur, yeni veri çekilemezse null/boş liste döner.</p>
 *
 * @see GateVelzonApiClient
 * @see com.scyborsa.api.controller.BilancoController
 */
@Slf4j
@Service
public class BilancoService {

    private final GateVelzonApiClient gateVelzonApiClient;
    private final ObjectMapper objectMapper;
    private final KatilimEndeksiService katilimEndeksiService;

    /** Finansal tablo servisi — MCP fallback için (opsiyonel, graceful degradation). */
    @Autowired(required = false)
    private FinansalTabloService finansalTabloService;

    /** Son bilanco raporlari listesi — volatile cache. */
    private volatile List<SonBilancoRaporDto> cachedSonRaporlar;
    /** Son raporlar cache timestamp (epoch ms). */
    private volatile long sonRaporlarCacheTimestamp;
    /** Son raporlar cache yenileme kilidi. */
    private final Object sonRaporlarLock = new Object();

    /** Bireysel bilanco raporlari cache (key: "SYMBOL:type"). */
    private final ConcurrentHashMap<String, CachedItem<BilancoDataDto>> reportCache = new ConcurrentHashMap<>();
    /** Bireysel rapor cache max boyutu. */
    private static final int MAX_REPORT_CACHE_SIZE = 200;

    /** Rasyo verileri cache (key: sembol). */
    private final ConcurrentHashMap<String, CachedItem<RasyoDetayDto>> rasyoCache = new ConcurrentHashMap<>();

    /** Son raporlar listesi TTL: seans ici 5 dakika. */
    private static final long SON_RAPORLAR_LIVE_TTL = 300_000L;
    /** Son raporlar listesi TTL: seans disi 1 saat. */
    private static final long SON_RAPORLAR_OFFHOURS_TTL = 3_600_000L;

    /** Bireysel rapor/rasyo TTL: seans ici 10 dakika. */
    private static final long REPORT_LIVE_TTL = 600_000L;
    /** Bireysel rapor/rasyo TTL: seans disi 2 saat. */
    private static final long REPORT_OFFHOURS_TTL = 7_200_000L;

    /**
     * Constructor injection ile bagimliliklari alir.
     *
     * @param gateVelzonApiClient    Gate Velzon API istemcisi
     * @param objectMapper           JSON parse icin Jackson ObjectMapper
     * @param katilimEndeksiService  katilim endeksi uyelik kontrolu servisi
     */
    public BilancoService(GateVelzonApiClient gateVelzonApiClient, ObjectMapper objectMapper,
                          KatilimEndeksiService katilimEndeksiService) {
        this.gateVelzonApiClient = gateVelzonApiClient;
        this.objectMapper = objectMapper;
        this.katilimEndeksiService = katilimEndeksiService;
    }

    // ── Son Raporlar ──

    /**
     * Tum hisselerin son bilanco rapor bilgilerini dondurur.
     *
     * @return son bilanco rapor listesi; veri yoksa bos liste
     */
    public List<SonBilancoRaporDto> getSonRaporlar() {
        refreshSonRaporlarIfStale();
        List<SonBilancoRaporDto> result = cachedSonRaporlar;
        return result != null ? result : List.of();
    }

    /**
     * Belirtilen sembolun son bilanco rapor bilgisini dondurur.
     *
     * @param symbol hisse sembolu (orn. "GARAN")
     * @return son bilanco rapor bilgisi; bulunamazsa null
     */
    public SonBilancoRaporDto getSonRapor(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        String upperSymbol = symbol.toUpperCase(Locale.ROOT);
        return getSonRaporlar().stream()
                .filter(r -> upperSymbol.equals(r.getSymbol()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Son raporlar cache'ini kontrol eder, suresi dolduysa yeniler.
     * Double-check locking ile thread-safe.
     */
    private void refreshSonRaporlarIfStale() {
        long ttl = BistCacheUtils.getAdaptiveTTL(SON_RAPORLAR_LIVE_TTL, SON_RAPORLAR_OFFHOURS_TTL);
        long now = System.currentTimeMillis();

        if (cachedSonRaporlar != null && (now - sonRaporlarCacheTimestamp) < ttl) {
            return;
        }

        synchronized (sonRaporlarLock) {
            ttl = BistCacheUtils.getAdaptiveTTL(SON_RAPORLAR_LIVE_TTL, SON_RAPORLAR_OFFHOURS_TTL);
            now = System.currentTimeMillis();
            if (cachedSonRaporlar != null && (now - sonRaporlarCacheTimestamp) < ttl) {
                return;
            }

            log.info("[BILANCO] Son raporlar cache yenileniyor (TTL: {}ms)", ttl);
            List<SonBilancoRaporDto> freshData = fetchSonRaporlar();

            if (freshData != null && !freshData.isEmpty()) {
                this.cachedSonRaporlar = Collections.unmodifiableList(freshData);
                this.sonRaporlarCacheTimestamp = System.currentTimeMillis();
                log.info("[BILANCO] Son raporlar cache guncellendi: {} rapor", freshData.size());
            } else {
                if (cachedSonRaporlar != null) {
                    this.sonRaporlarCacheTimestamp = System.currentTimeMillis();
                    log.warn("[BILANCO] Son raporlar cekilemedi, mevcut cache korunuyor");
                } else {
                    this.sonRaporlarCacheTimestamp = System.currentTimeMillis() - ttl + 60_000L;
                    log.warn("[BILANCO] Soguk baslatmada son raporlar cekilemedi, 1 dakika sonra yeniden denenecek");
                }
            }
        }
    }

    /**
     * Gate Velzon API'den son rapor listesini ceker.
     *
     * @return son rapor listesi; hata durumunda bos liste
     */
    private List<SonBilancoRaporDto> fetchSonRaporlar() {
        try {
            String responseBody = gateVelzonApiClient.get("/api/bilanco/son");
            JsonNode root = objectMapper.readTree(responseBody);

            JsonNode dataNode = root.get("data");
            if (dataNode == null || !dataNode.isArray()) {
                log.warn("[BILANCO] Son raporlar response'unda 'data' array bulunamadi");
                return List.of();
            }

            List<SonBilancoRaporDto> result = objectMapper.convertValue(
                    dataNode, new TypeReference<List<SonBilancoRaporDto>>() {});

            // Katilim endeksi zenginlestirmesi
            for (SonBilancoRaporDto dto : result) {
                dto.setKatilim(katilimEndeksiService.isKatilim(dto.getSymbol()));
            }

            log.info("[BILANCO] API'den {} son rapor alindi", result.size());
            return result;
        } catch (Exception e) {
            log.error("[BILANCO] Son raporlar cekme basarisiz", e);
            return List.of();
        }
    }

    // ── Bireysel Raporlar ──

    /**
     * Belirtilen sembolün bilançosunu döndürür.
     *
     * <p>Birincil kaynak: gate.velzon.tr REST API. Başarısız olursa ve
     * {@link FinansalTabloService} mevcutsa MCP fallback denenir.</p>
     *
     * @param symbol hisse sembolu (orn. "GARAN")
     * @return bilanco verisi; hata durumunda null
     */
    public BilancoDataDto getBilanco(String symbol) {
        BilancoDataDto result = getCachedReport(symbol, "bilanco", "/api/bilanco/" + symbol.toUpperCase(Locale.ROOT));

        // gate.velzon.tr başarılı olduysa direkt dön
        if (result != null) {
            return result;
        }

        // MCP fallback: FinansalTabloService üzerinden bilanço verisi dene
        return tryMcpBilancoFallback(symbol);
    }

    /**
     * MCP üzerinden bilanço verisi fallback denemesi.
     *
     * <p>{@link FinansalTabloService#getHisseBilanco(String)} kullanarak
     * Fintables MCP'den bilanço kalemlerini çeker ve {@link BilancoDataDto}'ya dönüştürür.</p>
     *
     * @param symbol hisse sembolü
     * @return bilanço verisi; MCP kullanılamıyorsa veya hata durumunda null
     */
    private BilancoDataDto tryMcpBilancoFallback(String symbol) {
        if (finansalTabloService == null) {
            return null;
        }

        try {
            log.info("[BILANCO] gate.velzon.tr başarısız, MCP fallback deneniyor: {}", symbol);
            List<FinansalTabloDto> bilancoKalemleri = finansalTabloService.getHisseBilanco(symbol);
            if (bilancoKalemleri == null || bilancoKalemleri.isEmpty()) {
                log.debug("[BILANCO] MCP fallback boş sonuç döndürdü: {}", symbol);
                return null;
            }

            // MCP bilanço kalemlerini BilancoDataDto'ya dönüştür
            BilancoDataDto dto = convertMcpToBilancoData(bilancoKalemleri, symbol);
            if (dto != null) {
                // MCP fallback sonucu cache'lenmez — yapısal olarak degraded (period, consolidation, link null).
                // Bir sonraki istekte gate.velzon.tr tekrar denenecek.
                log.info("[BILANCO] MCP fallback başarılı (cache'lenmedi): {} ({} kalem)", symbol, bilancoKalemleri.size());
            }
            return dto;
        } catch (Exception e) {
            log.warn("[BILANCO] MCP fallback hatası: {}, hata={}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * MCP finansal tablo DTO listesini BilancoDataDto'ya dönüştürür.
     *
     * <p>Fintables MCP bilanço kalemleri satır numarasına göre sıralıdır.
     * En güncel dönem (ilk yıl-ay grubu) kullanılır.</p>
     *
     * @param kalemleri MCP bilanço kalemleri
     * @param symbol    hisse sembolü
     * @return BilancoDataDto; dönüştürülemezse null
     */
    private BilancoDataDto convertMcpToBilancoData(List<FinansalTabloDto> kalemleri, String symbol) {
        if (kalemleri == null || kalemleri.isEmpty()) {
            return null;
        }

        try {
            // MCP kalemleri satır numarasına göre sıralı gelir — BilancoTableItemDto listesine dönüştür
            List<BilancoTableItemDto> tableItems = new ArrayList<>();
            Integer firstYear = null;
            Integer firstMonth = null;

            for (FinansalTabloDto kalem : kalemleri) {
                // Sadece ilk dönemi al (en güncel)
                if (firstYear == null) {
                    firstYear = kalem.getYil();
                    firstMonth = kalem.getAy();
                } else if (!Objects.equals(firstYear, kalem.getYil()) || !Objects.equals(firstMonth, kalem.getAy())) {
                    break;
                }

                Double numericAmount = kalem.getTryDonemsel() != null ? kalem.getTryDonemsel().doubleValue() : null;

                BilancoValueDto value = BilancoValueDto.builder()
                        .numericAmount(numericAmount)
                        .currency("TRY")
                        .build();

                tableItems.add(BilancoTableItemDto.builder()
                        .name(kalem.getKalem())
                        .value(value)
                        .numericValue(numericAmount)
                        .build());
            }

            if (tableItems.isEmpty()) {
                return null;
            }

            String donemStr = firstYear + "/" + firstMonth;

            BilancoTablesDto tables = BilancoTablesDto.builder()
                    .tableTypeName("Bilanço (MCP)")
                    .tableItems(tableItems)
                    .build();

            return BilancoDataDto.builder()
                    .title(symbol.toUpperCase(Locale.ROOT) + " " + donemStr + " Bilanço")
                    .year(firstYear)
                    .tables(tables)
                    .build();
        } catch (Exception e) {
            log.warn("[BILANCO] MCP bilanço dönüşüm hatası: {}", symbol, e);
            return null;
        }
    }

    /**
     * Belirtilen sembolun gelir tablosunu dondurur.
     *
     * @param symbol hisse sembolu (orn. "GARAN")
     * @return gelir tablosu verisi; hata durumunda null
     */
    public BilancoDataDto getGelirTablosu(String symbol) {
        return getCachedReport(symbol, "income", "/api/bilanco/" + symbol.toUpperCase(Locale.ROOT) + "/income");
    }

    /**
     * Belirtilen sembolun nakit akim tablosunu dondurur.
     *
     * @param symbol hisse sembolu (orn. "GARAN")
     * @return nakit akim tablosu verisi; hata durumunda null
     */
    public BilancoDataDto getNakitAkim(String symbol) {
        return getCachedReport(symbol, "cashflow", "/api/bilanco/" + symbol.toUpperCase(Locale.ROOT) + "/cashflow");
    }

    /**
     * Belirtilen sembolun tum finansal raporlarini (bilanco, gelir tablosu, nakit akim) dondurur.
     *
     * @param symbol hisse sembolu (orn. "GARAN")
     * @return rapor map'i (balanceSheet, incomeStatement, cashFlowStatement keyleri); hata durumunda bos map
     */
    public Map<String, BilancoDataDto> getAllReports(String symbol) {
        if (symbol == null || symbol.isBlank()) return Map.of();
        String upperSymbol = symbol.toUpperCase(Locale.ROOT);

        try {
            String responseBody = gateVelzonApiClient.get("/api/bilanco/" + upperSymbol + "/all");
            JsonNode root = objectMapper.readTree(responseBody);

            JsonNode reportsNode = root.get("reports");
            if (reportsNode == null || !reportsNode.isObject()) {
                log.warn("[BILANCO] Tum raporlar response'unda 'reports' object bulunamadi: {}", upperSymbol);
                return Map.of();
            }

            Map<String, BilancoDataDto> result = new LinkedHashMap<>();

            parseAndPutReport(reportsNode, "balanceSheet", result);
            parseAndPutReport(reportsNode, "incomeStatement", result);
            parseAndPutReport(reportsNode, "cashFlowStatement", result);

            // Bireysel cache'leri de guncelle
            if (result.containsKey("balanceSheet")) {
                putReportCache(upperSymbol, "bilanco", result.get("balanceSheet"));
            }
            if (result.containsKey("incomeStatement")) {
                putReportCache(upperSymbol, "income", result.get("incomeStatement"));
            }
            if (result.containsKey("cashFlowStatement")) {
                putReportCache(upperSymbol, "cashflow", result.get("cashFlowStatement"));
            }

            log.info("[BILANCO] {} icin {} rapor alindi (all)", upperSymbol, result.size());
            return result;
        } catch (Exception e) {
            log.error("[BILANCO] Tum raporlar cekme basarisiz: {}", upperSymbol, e);
            return Map.of();
        }
    }

    /**
     * JSON reports node'undan belirtilen key'e ait raporu parse edip result map'e ekler.
     *
     * <p>Gate API her rapor tipini {@code {message: {...}, data: {...}}} seklinde sarar.
     * Bu method ic ice {@code data} nesnesini cikarir ve parse eder.</p>
     *
     * @param reportsNode JSON reports node (balanceSheet, incomeStatement, cashFlowStatement iceren)
     * @param key         rapor key'i (orn. "balanceSheet")
     * @param result      sonuc map'i
     */
    private void parseAndPutReport(JsonNode reportsNode, String key, Map<String, BilancoDataDto> result) {
        JsonNode reportWrapper = reportsNode.get(key);
        if (reportWrapper != null && !reportWrapper.isNull()) {
            try {
                // Her rapor tipi {message: {...}, data: {...}} seklinde sarili
                JsonNode innerData = reportWrapper.get("data");
                JsonNode nodeToParse = (innerData != null && !innerData.isNull()) ? innerData : reportWrapper;
                BilancoDataDto report = objectMapper.convertValue(nodeToParse, BilancoDataDto.class);
                result.put(key, report);
            } catch (Exception e) {
                log.warn("[BILANCO] {} rapor parse hatasi", key, e);
            }
        }
    }

    /**
     * Cache'den rapor getirir, suresi dolduysa API'den ceker.
     *
     * @param symbol hisse sembolu
     * @param type   rapor tipi (bilanco, income, cashflow)
     * @param path   API path
     * @return rapor verisi; hata durumunda null
     */
    private BilancoDataDto getCachedReport(String symbol, String type, String path) {
        if (symbol == null || symbol.isBlank()) return null;
        String upperSymbol = symbol.toUpperCase(Locale.ROOT);
        String cacheKey = upperSymbol + ":" + type;

        long ttl = BistCacheUtils.getAdaptiveTTL(REPORT_LIVE_TTL, REPORT_OFFHOURS_TTL);
        CachedItem<BilancoDataDto> cached = reportCache.get(cacheKey);

        if (cached != null && !cached.isExpired(ttl)) {
            return cached.data;
        }

        try {
            String responseBody = gateVelzonApiClient.get(path);
            JsonNode root = objectMapper.readTree(responseBody);

            JsonNode dataNode = root.get("data");
            if (dataNode == null || dataNode.isNull()) {
                log.warn("[BILANCO] {} rapor response'unda 'data' bulunamadi: {}", type, upperSymbol);
                return null;
            }

            BilancoDataDto report = objectMapper.convertValue(dataNode, BilancoDataDto.class);
            putReportCache(upperSymbol, type, report);

            log.info("[BILANCO] {} icin {} raporu alindi", upperSymbol, type);
            return report;
        } catch (Exception e) {
            log.error("[BILANCO] {} rapor cekme basarisiz: {}", type, upperSymbol, e);
            // Stale cache varsa koru
            return cached != null ? cached.data : null;
        }
    }

    /**
     * Rapor cache'ine yeni veri ekler. Max boyut asilarsa en eski girdi cikarilir (ADR-027 evict-oldest).
     *
     * @param symbol hisse sembolu
     * @param type   rapor tipi
     * @param data   rapor verisi
     */
    private void putReportCache(String symbol, String type, BilancoDataDto data) {
        if (reportCache.size() >= MAX_REPORT_CACHE_SIZE) {
            log.info("[BILANCO] Rapor cache max boyut asildi ({}), en eski girdi cikariliyor", MAX_REPORT_CACHE_SIZE);
            evictOldest(reportCache);
        }
        reportCache.put(symbol + ":" + type, new CachedItem<>(data));
    }

    // ── Rasyo ──

    /**
     * Belirtilen sembolun finansal rasyolarini (oranlarini) dondurur.
     *
     * @param symbol hisse sembolu (orn. "GARAN")
     * @return rasyo detaylari; hata durumunda null
     */
    public RasyoDetayDto getRasyo(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        String upperSymbol = symbol.toUpperCase(Locale.ROOT);

        long ttl = BistCacheUtils.getAdaptiveTTL(REPORT_LIVE_TTL, REPORT_OFFHOURS_TTL);
        CachedItem<RasyoDetayDto> cached = rasyoCache.get(upperSymbol);

        if (cached != null && !cached.isExpired(ttl)) {
            return cached.data;
        }

        try {
            String responseBody = gateVelzonApiClient.get("/api/bilanco/" + upperSymbol + "/rasyo");
            JsonNode root = objectMapper.readTree(responseBody);

            JsonNode dataNode = root.get("data");
            if (dataNode == null || dataNode.isNull()) {
                log.warn("[BILANCO] Rasyo response'unda 'data' bulunamadi: {}", upperSymbol);
                return null;
            }

            RasyoDetayDto rasyo = objectMapper.convertValue(dataNode, RasyoDetayDto.class);

            if (rasyoCache.size() >= MAX_REPORT_CACHE_SIZE) {
                log.info("[BILANCO] Rasyo cache max boyut asildi ({}), en eski girdi cikariliyor", MAX_REPORT_CACHE_SIZE);
                evictOldest(rasyoCache);
            }
            rasyoCache.put(upperSymbol, new CachedItem<>(rasyo));

            log.info("[BILANCO] {} icin rasyo verisi alindi", upperSymbol);
            return rasyo;
        } catch (Exception e) {
            log.error("[BILANCO] Rasyo cekme basarisiz: {}", upperSymbol, e);
            return cached != null ? cached.data : null;
        }
    }

    /**
     * Cache'den en eski girdiyi cikarir (evict-oldest, ADR-027).
     * Thundering herd'u onlemek icin cache.clear() yerine kullanilir.
     *
     * @param cache temizlenecek ConcurrentHashMap
     * @param <T>   cache'lenen veri tipi
     */
    private <T> void evictOldest(ConcurrentHashMap<String, CachedItem<T>> cache) {
        String oldestKey = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<String, CachedItem<T>> entry : cache.entrySet()) {
            if (entry.getValue().timestamp < oldestTime) {
                oldestTime = entry.getValue().timestamp;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            cache.remove(oldestKey);
        }
    }

    // ── Inner Class ──

    /**
     * Zamanli cache ogesi. Veriyi ve olusturulma zamanini tutar.
     *
     * @param <T> cache'lenen veri tipi
     */
    private static class CachedItem<T> {
        /** Cache'lenen veri. */
        final T data;
        /** Cache'e eklendigi zaman (epoch ms). */
        final long timestamp;

        /**
         * Yeni cache ogesi olusturur.
         *
         * @param data cache'lenecek veri
         */
        CachedItem(T data) {
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }

        /**
         * Cache ogesinin suresinin dolup dolmadigini kontrol eder.
         *
         * @param ttl yasam suresi (milisaniye)
         * @return suresi dolduysa true
         */
        boolean isExpired(long ttl) {
            return (System.currentTimeMillis() - timestamp) >= ttl;
        }
    }
}
