package com.scyborsa.api.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.config.PresetStrategyRegistry;
import com.scyborsa.api.dto.market.HazirTaramaStockDto;
import com.scyborsa.api.dto.market.HazirTaramalarResponseDto;
import com.scyborsa.api.dto.market.PresetStrategyDto;
import com.scyborsa.api.enums.PresetStrategyEnum;
import com.scyborsa.api.service.client.VelzonApiClient;
import com.scyborsa.api.utils.BistCacheUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Hazir tarama stratejilerini calistiran servis.
 *
 * <p>api.velzon.tr proxy uzerinden ({@code GET /api/screener/ALL_STOCKS_WITH_INDICATORS})
 * tum BIST hisselerini teknik gostergelerle birlikte ceker,
 * sonuclari volatile cache'de tutar ve secilen stratejinin filtre kosuluna
 * gore eslesen hisseleri dondurur.</p>
 *
 * <p>Cache TTL: {@link BistCacheUtils#getAdaptiveTTL(long, long)} ile
 * seans ici 60s, seans disi 1 saat.</p>
 *
 * @see PresetStrategyEnum
 * @see PresetStrategyRegistry
 * @see HazirTaramalarResponseDto
 */
@Slf4j
@Service
public class HazirTaramalarService {

    /** Velzon API istemcisi (api.velzon.tr proxy). */
    private final VelzonApiClient velzonApiClient;

    /** JSON parse icin Jackson ObjectMapper. */
    private final ObjectMapper objectMapper;

    /** Strateji filtre predicate registry'si. */
    private final PresetStrategyRegistry strategyRegistry;

    /** api.velzon.tr screener endpoint path'i. */
    private static final String SCREENER_PATH = "/api/screener/ALL_STOCKS_WITH_INDICATORS";

    /** Cache: tum hisselerin kolon-deger map listesi. */
    private volatile List<Map<String, Object>> cachedStocks;

    /** Cache: son guncelleme zamani (epoch millis). */
    private volatile long cacheTimestamp;

    /** Cache yenileme icin senkronizasyon kilidi. */
    private final Object cacheLock = new Object();

    /**
     * Constructor injection ile bagimliliklari alir.
     *
     * @param velzonApiClient  api.velzon.tr REST istemcisi
     * @param objectMapper     JSON parse icin Jackson ObjectMapper
     * @param strategyRegistry strateji filtre registry'si
     */
    public HazirTaramalarService(VelzonApiClient velzonApiClient,
                                  ObjectMapper objectMapper,
                                  PresetStrategyRegistry strategyRegistry) {
        this.velzonApiClient = velzonApiClient;
        this.objectMapper = objectMapper;
        this.strategyRegistry = strategyRegistry;
    }

    /**
     * Belirtilen strateji koduna gore tarama yapar ve eslesen hisseleri dondurur.
     *
     * <p>Cache'deki verilere strateji filtre predicate'ini uygular.
     * Cache TTL dolmussa otomatik olarak yenilenir.</p>
     *
     * @param strategyCode strateji kodu (orn. "rsi_oversold")
     * @return tarama sonucu; gecersiz strateji kodunda bos sonuc
     */
    public HazirTaramalarResponseDto scan(String strategyCode) {
        PresetStrategyEnum strategy = PresetStrategyEnum.fromCode(strategyCode);
        if (strategy == null) {
            log.warn("[HAZIR-TARAMALAR] Bilinmeyen strateji kodu: {}", strategyCode);
            return HazirTaramalarResponseDto.builder()
                    .strategy(strategyCode)
                    .strategyDisplayName("Bilinmeyen Strateji")
                    .category("")
                    .stocks(List.of())
                    .totalCount(0)
                    .build();
        }

        refreshCacheIfStale();

        List<Map<String, Object>> stocks = cachedStocks;
        if (stocks == null || stocks.isEmpty()) {
            log.warn("[HAZIR-TARAMALAR] Cache bos, tarama yapilamiyor: {}", strategyCode);
            return HazirTaramalarResponseDto.builder()
                    .strategy(strategy.getCode())
                    .strategyDisplayName(strategy.getDisplayName())
                    .category(strategy.getCategory())
                    .stocks(List.of())
                    .totalCount(0)
                    .build();
        }

        Predicate<Map<String, Object>> predicate = strategyRegistry.getPredicate(strategy);
        List<HazirTaramaStockDto> matchedStocks = stocks.stream()
                .filter(predicate)
                .map(this::toDto)
                .sorted(Comparator.comparingDouble((HazirTaramaStockDto s) ->
                        s.getChangePercent() != null ? s.getChangePercent() : 0.0).reversed())
                .collect(Collectors.toList());

        log.info("[HAZIR-TARAMALAR] {} stratejisi: {}/{} hisse eslesti",
                strategyCode, matchedStocks.size(), stocks.size());

        return HazirTaramalarResponseDto.builder()
                .strategy(strategy.getCode())
                .strategyDisplayName(strategy.getDisplayName())
                .category(strategy.getCategory())
                .stocks(matchedStocks)
                .totalCount(matchedStocks.size())
                .build();
    }

    /**
     * Tum mevcut stratejileri DTO listesi olarak dondurur.
     *
     * @return strateji tanim listesi
     */
    public List<PresetStrategyDto> getStrategies() {
        return Arrays.stream(PresetStrategyEnum.values())
                .map(s -> PresetStrategyDto.builder()
                        .code(s.getCode())
                        .displayName(s.getDisplayName())
                        .description(s.getDescription())
                        .category(s.getCategory())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * Cache TTL dolmussa yeni veri ceker.
     *
     * <p>Synchronized blok ile ayni anda birden fazla yenileme engellenir.
     * Double-check locking pattern uygulanir.</p>
     */
    private void refreshCacheIfStale() {
        long ttl = BistCacheUtils.getAdaptiveTTL(60_000L, 3_600_000L);
        long now = System.currentTimeMillis();

        if (cachedStocks != null && (now - cacheTimestamp) < ttl) {
            return;
        }

        synchronized (cacheLock) {
            now = System.currentTimeMillis();
            if (cachedStocks != null && (now - cacheTimestamp) < ttl) {
                return;
            }

            log.info("[HAZIR-TARAMALAR] Cache yenileniyor (TTL: {}ms)", ttl);
            List<Map<String, Object>> freshData = fetchAllStocksWithIndicators();

            if (freshData != null && !freshData.isEmpty()) {
                this.cachedStocks = Collections.unmodifiableList(freshData);
                this.cacheTimestamp = System.currentTimeMillis();
                log.info("[HAZIR-TARAMALAR] Cache guncellendi: {} hisse", freshData.size());
            } else {
                log.warn("[HAZIR-TARAMALAR] Veri cekilemedi, mevcut cache korunuyor");
            }
        }
    }

    /**
     * api.velzon.tr proxy uzerinden tum BIST hisselerini teknik gostergelerle
     * birlikte ceker.
     *
     * <p>Response formati: {@code {"data": [{"s": "BIST:GARAN", "d": [...]}], "columns": [...]}}</p>
     *
     * @return kolon-deger map listesi; hata durumunda bos liste
     */
    private List<Map<String, Object>> fetchAllStocksWithIndicators() {
        try {
            String responseBody = velzonApiClient.get(SCREENER_PATH);
            return parseResponse(responseBody);
        } catch (Exception e) {
            log.error("[HAZIR-TARAMALAR] api.velzon.tr screener cagrisi basarisiz", e);
            return List.of();
        }
    }

    /**
     * api.velzon.tr screener response'unu parse ederek kolon-deger map listesine donusturur.
     *
     * <p>Response'da {@code columns} dizisi varsa dinamik kolon eslesmesi yapilir.
     * Yoksa {@code d[]} array'i sabit indeks ile parse edilir.</p>
     *
     * @param responseBody API'den donen JSON response
     * @return kolon-deger map listesi
     */
    private List<Map<String, Object>> parseResponse(String responseBody) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // Response formati: {"data": {"totalCount":N, "data":[...], "columns":[...]}, ...}
            JsonNode dataWrapper = root.get("data");
            if (dataWrapper == null || !dataWrapper.isObject()) {
                log.warn("[HAZIR-TARAMALAR] Response'da data objesi bulunamadi");
                return result;
            }

            JsonNode dataArray = dataWrapper.get("data");
            if (dataArray == null || !dataArray.isArray()) {
                log.warn("[HAZIR-TARAMALAR] Response'da data.data array bulunamadi");
                return result;
            }

            // Dinamik kolon mapping: response'daki data.columns array'ini kullan
            List<String> columns = new ArrayList<>();
            JsonNode columnsNode = dataWrapper.get("columns");
            if (columnsNode != null && columnsNode.isArray()) {
                for (JsonNode col : columnsNode) {
                    columns.add(col.asText());
                }
            }

            for (int i = 0; i < dataArray.size(); i++) {
                JsonNode item = dataArray.get(i);
                if (item == null) continue;

                JsonNode dArray = item.get("d");
                if (dArray == null || !dArray.isArray()) continue;

                Map<String, Object> row = new HashMap<>();

                // ticker: s field -> "BIST:GARAN" -> "GARAN"
                JsonNode sNode = item.get("s");
                if (sNode != null) {
                    row.put("ticker", sNode.asText().replace("BIST:", ""));
                }

                // Kolon-deger eslesmesi
                for (int j = 0; j < dArray.size(); j++) {
                    JsonNode val = dArray.get(j);
                    String colName = (j < columns.size()) ? columns.get(j) : "col_" + j;

                    if (val == null || val.isNull()) {
                        row.put(colName, null);
                    } else if (val.isNumber()) {
                        row.put(colName, val.numberValue());
                    } else if (val.isTextual()) {
                        row.put(colName, val.asText());
                    } else {
                        row.put(colName, val.toString());
                    }
                }

                result.add(row);
            }

            log.debug("[HAZIR-TARAMALAR] {} hisse parse edildi", result.size());
        } catch (Exception e) {
            log.error("[HAZIR-TARAMALAR] Response parse hatasi", e);
        }
        return result;
    }

    /**
     * Kolon-deger map'ini {@link HazirTaramaStockDto}'ya donusturur.
     *
     * @param row tek bir hissenin kolon-deger map'i
     * @return hisse DTO'su
     */
    private HazirTaramaStockDto toDto(Map<String, Object> row) {
        return HazirTaramaStockDto.builder()
                .stockCode(getString(row, "name"))
                .stockName(getString(row, "description"))
                .logoid(getString(row, "logoid"))
                .price(getDouble(row, "close"))
                .changePercent(getDouble(row, "change"))
                .relativeVolume(getDouble(row, "relative_volume_10d_calc"))
                .avgVolume10d(getDouble(row, "average_volume_10d_calc"))
                .avgVolume60d(getDouble(row, "average_volume_60d_calc"))
                .avgVolume90d(getDouble(row, "average_volume_90d_calc"))
                .build();
    }

    /**
     * Map'ten null-safe sayi cikarimi yapar.
     *
     * @param row map
     * @param key anahtar
     * @return sayi degeri; null veya sayi degilse {@code null}
     */
    private Double getDouble(Map<String, Object> row, String key) {
        Object val = row.get(key);
        if (val instanceof Number n) return n.doubleValue();
        return null;
    }

    /**
     * Map'ten null-safe string cikarimi yapar.
     *
     * @param row map
     * @param key anahtar
     * @return string degeri; null ise {@code null}
     */
    private String getString(Map<String, Object> row, String key) {
        Object val = row.get(key);
        if (val instanceof String s) return s;
        return val != null ? val.toString() : null;
    }
}
