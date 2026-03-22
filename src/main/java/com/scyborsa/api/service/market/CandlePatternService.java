package com.scyborsa.api.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.dto.market.CandlePatternStockDto;
import com.scyborsa.api.dto.sector.SectorStockDto;
import com.scyborsa.api.service.client.VelzonApiClient;
import com.scyborsa.api.utils.BistCacheUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Mum formasyonu tarama servisi.
 *
 * <p>Velzon API uzerinden mum formasyonu (candlestick pattern) tarama verilerini ceker,
 * per-period adaptive TTL ile cache'ler, logoid ile zenginlestirir ve patternCount'a
 * gore siralar.</p>
 *
 * <p>Cache stratejisi: Seans ici 90s, seans disi 1 saat
 * ({@link BistCacheUtils#getAdaptiveTTL(long, long)}).</p>
 *
 * @see com.scyborsa.api.dto.market.CandlePatternStockDto
 * @see com.scyborsa.api.service.client.VelzonApiClient
 */
@Slf4j
@Service
public class CandlePatternService {

    private final VelzonApiClient velzonApiClient;
    private final ObjectMapper objectMapper;
    private final Bist100Service bist100Service;

    /** Candlestick pattern API path sablonu. */
    private static final String CANDLE_PATTERN_PATH = "/api/screener/candlestick-patterns/";
    /** ALL_STOCKS screener path (logoid icin). */
    private static final String SCREENER_PATH = "/api/screener/ALL_STOCKS_WITH_INDICATORS";

    /** Gecerli periyot degerleri. */
    private static final Set<String> VALID_PERIODS = Set.of("15", "1H", "4H", "1D", "1W");

    /** Per-period kilit nesneleri (String.intern() yerine guvenli locking). */
    private final ConcurrentHashMap<String, Object> periodLocks = new ConcurrentHashMap<>();

    /** Per-period cache'lenmis formasyon listesi. */
    private final ConcurrentHashMap<String, List<CandlePatternStockDto>> periodCache = new ConcurrentHashMap<>();
    /** Per-period cache zaman damgalari (epoch ms). */
    private final ConcurrentHashMap<String, Long> periodCacheTimestamps = new ConcurrentHashMap<>();

    /** StockCode -> logoid esleme cache'i. */
    private volatile Map<String, String> logoidCache = Map.of();
    /** Logoid cache son guncelleme zamani. */
    private volatile long logoidCacheTimestamp;
    /** Logoid cache yenileme icin kilit nesnesi. */
    private final Object logoidLock = new Object();

    /**
     * CandlePatternService constructor.
     *
     * @param velzonApiClient api.velzon.tr REST istemcisi
     * @param objectMapper    JSON parser
     * @param bist100Service  BIST endeks hisse verileri servisi (fiyat/degisim/hacim zenginlestirme icin)
     */
    public CandlePatternService(VelzonApiClient velzonApiClient, ObjectMapper objectMapper,
                                Bist100Service bist100Service) {
        this.velzonApiClient = velzonApiClient;
        this.objectMapper = objectMapper;
        this.bist100Service = bist100Service;
    }

    /**
     * Belirtilen periyot icin mum formasyonu tarama sonuclarini dondurur.
     *
     * <p>Sonuclar patternCount'a gore azalan sirada siralanir, logoid ile zenginlestirilir
     * ve Bist100Service cache'inden fiyat/degisim/hacim bilgisiyle zenginlestirilir.
     * Gecersiz periyot verildiginde bos sonuc doner.</p>
     *
     * @param period tarama periyodu (15, 1H, 4H, 1D, 1W)
     * @return tarama sonuclari: stocks listesi, totalCount ve period iceren Map
     */
    public Map<String, Object> getPatterns(String period) {
        if (!VALID_PERIODS.contains(period)) {
            log.warn("[CANDLE-PATTERN] Gecersiz periyot: {}", period);
            return Map.of("stocks", List.of(), "totalCount", 0, "period", period);
        }

        refreshCacheIfStale(period);
        refreshLogoidCacheIfStale();

        List<CandlePatternStockDto> stocks = periodCache.getOrDefault(period, List.of());

        // Fiyat/degisim/hacim icin priceMap olustur
        Map<String, SectorStockDto> priceMap = Map.of();
        try {
            List<SectorStockDto> allStocks = bist100Service.getAllStocks();
            priceMap = allStocks.stream()
                    .collect(Collectors.toMap(SectorStockDto::getTicker, Function.identity(), (a, b) -> a));
        } catch (Exception e) {
            log.warn("[CANDLE-PATTERN] Fiyat verisi alinamadi, devam ediliyor", e);
        }

        // Logoid + fiyat zenginlestirme — cache'deki DTO'lari mutate etmemek icin kopya olustur
        Map<String, String> logoidMap = logoidCache;
        List<CandlePatternStockDto> result = new ArrayList<>(stocks.size());
        for (CandlePatternStockDto s : stocks) {
            SectorStockDto sd = priceMap.get(s.getSymbol());
            CandlePatternStockDto copy = CandlePatternStockDto.builder()
                    .symbol(s.getSymbol())
                    .logoid(logoidMap.getOrDefault(s.getSymbol(), s.getLogoid()))
                    .patterns(s.getPatterns() != null ? List.copyOf(s.getPatterns()) : List.of())
                    .patternCount(s.getPatternCount())
                    .patternValues(s.getPatternValues() != null ? Map.copyOf(s.getPatternValues()) : Map.of())
                    .price(sd != null ? sd.getPrice() : null)
                    .changePercent(sd != null ? sd.getChangePercent() : null)
                    .volume(sd != null ? sd.getVolume() : null)
                    .open(sd != null ? sd.getOpen() : null)
                    .build();
            result.add(copy);
        }

        return Map.of("stocks", result, "totalCount", result.size(), "period", period);
    }

    /**
     * Belirtilen periyot icin cache suresi dolduysa Velzon API'den yeni veri ceker.
     * Double-check locking ile thread-safe cache yenileme.
     *
     * @param period tarama periyodu
     */
    private void refreshCacheIfStale(String period) {
        long ttl = BistCacheUtils.getAdaptiveTTL(90_000L, 3_600_000L);
        long now = System.currentTimeMillis();
        Long lastUpdate = periodCacheTimestamps.get(period);

        if (lastUpdate != null && (now - lastUpdate) < ttl) {
            return;
        }

        // Double-check locking: period.intern() ile per-period kilit
        synchronized (periodLocks.computeIfAbsent(period, k -> new Object())) {
            ttl = BistCacheUtils.getAdaptiveTTL(90_000L, 3_600_000L);
            now = System.currentTimeMillis();
            lastUpdate = periodCacheTimestamps.get(period);
            if (lastUpdate != null && (now - lastUpdate) < ttl) {
                return;
            }

            log.info("[CANDLE-PATTERN] Cache yenileniyor: period={}, TTL={}ms", period, ttl);
            List<CandlePatternStockDto> freshData = fetchFromVelzon(period);

            if (freshData != null && !freshData.isEmpty()) {
                periodCache.put(period, Collections.unmodifiableList(freshData));
                periodCacheTimestamps.put(period, System.currentTimeMillis());
                log.info("[CANDLE-PATTERN] Cache guncellendi: period={}, {} hisse", period, freshData.size());
            } else {
                log.warn("[CANDLE-PATTERN] Veri cekilemedi: period={}, mevcut cache korunuyor", period);
            }
        }
    }

    /**
     * Logoid cache'ini gerektiginde yeniler (6 saatlik TTL).
     * VelzonApiClient uzerinden ALL_STOCKS verisinden logoid bilgisi cikarilir.
     */
    private void refreshLogoidCacheIfStale() {
        long now = System.currentTimeMillis();
        if (!logoidCache.isEmpty() && (now - logoidCacheTimestamp) < 21_600_000L) {
            return;
        }

        synchronized (logoidLock) {
            // Double-check locking
            now = System.currentTimeMillis();
            if (!logoidCache.isEmpty() && (now - logoidCacheTimestamp) < 21_600_000L) {
                return;
            }

        try {
            String responseBody = velzonApiClient.get(SCREENER_PATH);
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode dataWrapper = root.get("data");
            if (dataWrapper == null || !dataWrapper.isObject()) {
                this.logoidCacheTimestamp = System.currentTimeMillis();
                return;
            }

            JsonNode columns = dataWrapper.get("columns");
            JsonNode dataArray = dataWrapper.get("data");
            if (columns == null || dataArray == null) {
                this.logoidCacheTimestamp = System.currentTimeMillis();
                return;
            }

            int nameIdx = -1, logoidIdx = -1;
            for (int i = 0; i < columns.size(); i++) {
                String col = columns.get(i).asText();
                if ("name".equals(col)) nameIdx = i;
                else if ("logoid".equals(col)) logoidIdx = i;
            }
            if (nameIdx < 0 || logoidIdx < 0) {
                this.logoidCacheTimestamp = System.currentTimeMillis();
                return;
            }

            Map<String, String> map = new HashMap<>();
            for (JsonNode item : dataArray) {
                JsonNode d = item.get("d");
                if (d == null || !d.isArray()) continue;
                if (nameIdx < d.size() && logoidIdx < d.size()) {
                    String name = d.get(nameIdx).asText(null);
                    String logoid = d.get(logoidIdx).asText(null);
                    if (name != null && logoid != null) {
                        map.put(name, logoid);
                    }
                }
            }

            this.logoidCacheTimestamp = System.currentTimeMillis();
            if (!map.isEmpty()) {
                this.logoidCache = Collections.unmodifiableMap(map);
                log.info("[CANDLE-PATTERN] Logoid cache guncellendi: {} hisse", map.size());
            }
        } catch (Exception e) {
            log.warn("[CANDLE-PATTERN] Logoid cache yenileme basarisiz", e);
        }
        } // synchronized (logoidLock)
    }

    /**
     * Velzon API'den belirtilen periyot icin mum formasyonu verilerini ceker.
     *
     * @param period tarama periyodu
     * @return formasyon listesi (patternCount DESC); hata durumunda bos liste
     */
    private List<CandlePatternStockDto> fetchFromVelzon(String period) {
        try {
            String responseBody = velzonApiClient.get(CANDLE_PATTERN_PATH + period);
            return parseResponse(responseBody);
        } catch (Exception e) {
            log.error("[CANDLE-PATTERN] Velzon API cagrisi basarisiz: period={}", period, e);
            return List.of();
        }
    }

    /**
     * Velzon API response JSON'unu parse eder.
     *
     * <p>Defensif parse: once "stocks" anahtarini arar, yoksa "data", yoksa root'u array olarak dener.</p>
     *
     * @param responseBody ham JSON string
     * @return parse edilmis ve filtrelenmis formasyon listesi (patternCount DESC)
     */
    private List<CandlePatternStockDto> parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // Defensif: "stocks" -> "data" -> root array
            JsonNode stocksArray;
            if (root.has("stocks") && root.get("stocks").isArray()) {
                stocksArray = root.get("stocks");
            } else if (root.has("data") && root.get("data").isArray()) {
                stocksArray = root.get("data");
            } else if (root.isArray()) {
                stocksArray = root;
            } else {
                log.warn("[CANDLE-PATTERN] Beklenmeyen response formati");
                return List.of();
            }

            List<CandlePatternStockDto> results = new ArrayList<>();
            for (JsonNode item : stocksArray) {
                CandlePatternStockDto dto = parseStockItem(item);
                if (dto != null && dto.getPatternCount() >= 1 && dto.getPatterns() != null && !dto.getPatterns().isEmpty()) {
                    results.add(dto);
                }
            }

            // patternCount DESC siralama
            results.sort(Comparator.comparingInt(CandlePatternStockDto::getPatternCount).reversed());

            return results;
        } catch (Exception e) {
            log.error("[CANDLE-PATTERN] Response parse hatasi", e);
            return List.of();
        }
    }

    /**
     * Tek bir hisse JSON item'ini parse eder.
     *
     * <p>"BIST:" prefix'i symbol'den, "Candle." prefix'i pattern key'lerden cikarilir.</p>
     *
     * @param item JSON node (tek hisse)
     * @return parse edilmis DTO veya null
     */
    private CandlePatternStockDto parseStockItem(JsonNode item) {
        try {
            // Symbol: "BIST:" prefix strip
            String rawSymbol = item.has("symbol") ? item.get("symbol").asText("") : "";
            String symbol = rawSymbol.startsWith("BIST:") ? rawSymbol.substring(5) : rawSymbol;
            if (symbol.isBlank()) return null;

            // Patterns: "Candle." prefix strip
            List<String> patterns = new ArrayList<>();
            if (item.has("patterns") && item.get("patterns").isArray()) {
                for (JsonNode p : item.get("patterns")) {
                    String patternName = p.asText("");
                    patterns.add(stripCandlePrefix(patternName));
                }
            }

            // PatternValues: "Candle." prefix strip
            Map<String, Integer> patternValues = new LinkedHashMap<>();
            if (item.has("patternValues") && item.get("patternValues").isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = item.get("patternValues").fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    String key = stripCandlePrefix(entry.getKey());
                    int value = entry.getValue().asInt(0);
                    patternValues.put(key, value);
                }
            }

            // PatternCount
            int patternCount = item.has("patternCount")
                    ? item.get("patternCount").asInt(0)
                    : patterns.size();

            return CandlePatternStockDto.builder()
                    .symbol(symbol)
                    .patterns(patterns)
                    .patternCount(patternCount)
                    .patternValues(patternValues)
                    .build();
        } catch (Exception e) {
            log.debug("[CANDLE-PATTERN] Hisse parse hatasi: {}", e.getMessage());
            return null;
        }
    }

    /**
     * "Candle." prefix'ini kaldirır.
     *
     * @param patternName ham formasyon adi
     * @return temizlenmis formasyon adi
     */
    private String stripCandlePrefix(String patternName) {
        return patternName.startsWith("Candle.") ? patternName.substring(7) : patternName;
    }
}
