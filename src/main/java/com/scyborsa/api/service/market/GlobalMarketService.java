package com.scyborsa.api.service.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.dto.market.GlobalMarketDto;
import com.scyborsa.api.dto.screener.TvScreenerResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Global piyasa verilerini saglayan servis.
 *
 * <p>TradingView Scanner API {@code /global/scan} endpoint'ine dogrudan
 * HTTP istegi gondererek emtia, doviz, kripto ve uluslararasi endeks
 * verilerini ceker.</p>
 *
 * <p>Sonuclar volatile cache ile saklanir (60 saniye TTL).</p>
 *
 * @see GlobalMarketDto
 */
@Slf4j
@Service
public class GlobalMarketService {

    /** TradingView global scan endpoint URL'i. */
    private static final String GLOBAL_SCAN_URL = "https://scanner.tradingview.com/global/scan";

    /** Cache TTL (milisaniye cinsinden): 60 saniye. */
    private static final long CACHE_TTL_MS = 60_000L;

    /** HTTP baglanti timeout suresi (saniye). */
    private static final int CONNECT_TIMEOUT_SECONDS = 10;

    /** HTTP istek timeout suresi (saniye). */
    private static final int REQUEST_TIMEOUT_SECONDS = 15;

    /** Scan body JSON — global piyasa enstrumanlari. */
    private static final String SCAN_BODY = """
            {
              "symbols": {
                "tickers": [
                  "TVC:GOLD", "TVC:SILVER", "NYMEX:CL1!", "TVC:DJI", "SP:SPX",
                  "TVC:DXY", "TVC:NI225", "TVC:KOSPI", "XETR:DAX", "CBOE:VIX",
                  "FX:USDTRY", "FX:EURTRY", "BITSTAMP:BTCUSD", "BITSTAMP:ETHUSD",
                  "AMEX:EEM", "AMEX:TUR",
                  "COMEX:HG1!", "NYMEX:PA1!", "NYMEX:PL1!",
                  "BLACKBULL:BRENT"
                ]
              },
              "columns": ["name", "close", "change", "description"],
              "options": {"lang": "tr"}
            }
            """;

    /**
     * Sembol → kategori eslestirme tablosu.
     * <p>Exchange prefix cikarilmis sembol adi kullanilir.</p>
     */
    private static final Map<String, String> CATEGORY_MAP = Map.ofEntries(
            Map.entry("GOLD", "EMTIA"),
            Map.entry("SILVER", "EMTIA"),
            Map.entry("CL1!", "EMTIA"),
            Map.entry("HG1!", "EMTIA"),
            Map.entry("PA1!", "EMTIA"),
            Map.entry("PL1!", "EMTIA"),
            Map.entry("BRENT", "EMTIA"),
            Map.entry("USDTRY", "DOVIZ"),
            Map.entry("EURTRY", "DOVIZ"),
            Map.entry("BTCUSD", "KRIPTO"),
            Map.entry("ETHUSD", "KRIPTO"),
            Map.entry("DJI", "ENDEKS"),
            Map.entry("SPX", "ENDEKS"),
            Map.entry("DXY", "ENDEKS"),
            Map.entry("NI225", "ENDEKS"),
            Map.entry("KOSPI", "ENDEKS"),
            Map.entry("DAX", "ENDEKS"),
            Map.entry("VIX", "ENDEKS"),
            Map.entry("EEM", "ENDEKS"),
            Map.entry("TUR", "ENDEKS")
    );

    /** JSON parse icin Jackson ObjectMapper. */
    private final ObjectMapper objectMapper;

    /** HTTP istekleri icin Java 11 HttpClient. */
    private final HttpClient httpClient;

    /** Cache: global piyasa listesi. Volatile ile thread-safe erisim. */
    private volatile List<GlobalMarketDto> cachedMarkets;

    /** Cache: son guncelleme zamani (epoch millis). */
    private volatile long cacheTimestamp;

    /**
     * Constructor injection ile bagimliliklari alir ve HTTP client olusturur.
     *
     * @param objectMapper JSON parse icin Jackson ObjectMapper
     */
    public GlobalMarketService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .build();
    }

    /**
     * Global piyasa verilerini dondurur.
     *
     * <p>TradingView Scanner API'den emtia, doviz, kripto ve uluslararasi
     * endeks verilerini alir. Sonuclar volatile cache ile 60 saniye saklanir.</p>
     *
     * @return global piyasa listesi; hata durumunda bos liste
     */
    public synchronized List<GlobalMarketDto> getGlobalMarkets() {
        // Cache kontrolu (synchronized ile double-fetch onlenir)
        long now = System.currentTimeMillis();
        if (cachedMarkets != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
            log.debug("[GLOBAL-MARKET] Cache hit, kalan sure: {}s",
                    (CACHE_TTL_MS - (now - cacheTimestamp)) / 1000);
            return cachedMarkets;
        }

        log.info("[GLOBAL-MARKET] Global piyasa verileri TradingView API'den cekiliyor");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GLOBAL_SCAN_URL))
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Origin", "https://tr.tradingview.com")
                    .header("Referer", "https://tr.tradingview.com/")
                    .POST(HttpRequest.BodyPublishers.ofString(SCAN_BODY))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
                log.error("[GLOBAL-MARKET] API hata kodu: {}", response.statusCode());
                return cachedMarkets != null ? cachedMarkets : List.of();
            }

            TvScreenerResponse tvResponse = objectMapper.readValue(
                    response.body(), TvScreenerResponse.class);

            if (tvResponse == null || tvResponse.getData() == null) {
                log.warn("[GLOBAL-MARKET] API response bos veya gecersiz");
                return cachedMarkets != null ? cachedMarkets : List.of();
            }

            List<GlobalMarketDto> result = new ArrayList<>();

            for (TvScreenerResponse.DataItem item : tvResponse.getData()) {
                GlobalMarketDto dto = parseItem(item);
                if (dto != null) {
                    result.add(dto);
                }
            }

            // Cache guncelle
            this.cachedMarkets = Collections.unmodifiableList(result);
            this.cacheTimestamp = System.currentTimeMillis();

            log.info("[GLOBAL-MARKET] {} global piyasa verisi basariyla yuklendi", result.size());

            return cachedMarkets;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[GLOBAL-MARKET] Istek kesildi: {}", e.getMessage());
            return cachedMarkets != null ? cachedMarkets : List.of();
        } catch (Exception e) {
            log.error("[GLOBAL-MARKET] Global piyasa verisi alinamadi: {}", e.getMessage(), e);
            return cachedMarkets != null ? cachedMarkets : List.of();
        }
    }

    /**
     * Tek bir data satirini parse ederek {@link GlobalMarketDto} olusturur.
     *
     * <p>TradingView screener formati: {@code s} alani sembol (orn. "TVC:GOLD"),
     * {@code d[]} array'i kolon degerlerini icerir (name, close, change, description).</p>
     *
     * @param item TradingView response'undan tek bir data satiri
     * @return parse edilmis DTO; gecersiz veri durumunda {@code null}
     */
    private GlobalMarketDto parseItem(TvScreenerResponse.DataItem item) {
        if (item == null || item.getS() == null || item.getD() == null || item.getD().size() < 4) {
            return null;
        }

        String symbol = extractSymbol(item.getS());
        if (symbol == null) {
            return null;
        }

        List<Object> d = item.getD();

        // d[0] = name, d[1] = close, d[2] = change%, d[3] = description
        String description = d.get(3) != null ? d.get(3).toString() : symbol;
        Double lastPrice = toDoubleOrNull(d.get(1));
        Double dailyChange = toDoubleOrNull(d.get(2));
        String category = CATEGORY_MAP.getOrDefault(symbol, "ENDEKS");

        return new GlobalMarketDto(symbol, description, lastPrice, dailyChange, category);
    }

    /**
     * Sembol degerini parse eder ve exchange prefix'ini cikarir.
     *
     * <p>"TVC:GOLD" → "GOLD", "FX:USDTRY" → "USDTRY" seklinde
     * iki noktadan sonrasini alir.</p>
     *
     * @param raw sembol ham degeri (orn. "TVC:GOLD")
     * @return temizlenmis sembol; gecersiz veri durumunda {@code null}
     */
    private String extractSymbol(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int colonIdx = raw.indexOf(':');
        if (colonIdx >= 0 && colonIdx < raw.length() - 1) {
            return raw.substring(colonIdx + 1);
        }
        return raw;
    }

    /**
     * Object degerini double'a donusturur.
     *
     * @param value donusturulecek deger
     * @return double deger; null veya donusturulemezse 0.0
     */
    private Double toDoubleOrNull(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }
}
