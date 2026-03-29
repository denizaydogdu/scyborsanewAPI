package com.scyborsa.api.service.market;

import com.scyborsa.api.service.client.VelzonApiClient;
import com.scyborsa.api.utils.BistCacheUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.scyborsa.api.dto.market.IndexPerformanceDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Borsa endeks performans verilerini saglayan servis.
 *
 * <p>Velzon API uzerinden BIST endekslerinin (XU100, XBANK, vb.) coklu zaman
 * dilimindeki performans verilerini ceker ve {@link IndexPerformanceDto} listesi olarak sunar.</p>
 *
 * <p>Velzon pinescreener formati: her endeks dogrudan {@code symbol}, {@code plot6},
 * {@code plot16} gibi key'lerle gelir. TradingView screener {@code d[]} array formati
 * KULLANILMAZ.</p>
 *
 * <p>Plot mapping:</p>
 * <ul>
 *   <li>{@code plot16} → lastPrice (close)</li>
 *   <li>{@code plot6} → dailyChange%</li>
 *   <li>{@code plot7} → weeklyChange%</li>
 *   <li>{@code plot8} → monthlyChange%</li>
 *   <li>{@code plot9} → quarterlyChange%</li>
 *   <li>{@code plot10} → sixMonthChange%</li>
 *   <li>{@code plot12} → yearlyChange%</li>
 * </ul>
 *
 * <p>Sonuclar volatile cache ile saklanir. Dinamik TTL stratejisi: seans
 * saatlerinde (09:50-18:25) 60 saniye, seans disinda bir sonraki seans acilisina kadar
 * ({@link BistCacheUtils#getDynamicOffhoursTTL(long, long)}).</p>
 *
 * @see VelzonApiClient
 * @see IndexPerformanceDto
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexPerformanceService {

    /** Velzon API endpoint path'i. */
    private static final String INDEXES_PERFORMANCE_PATH = "/api/pinescreener/velzon_indexes_performance";

    /** Seans ici cache TTL (milisaniye): 60 saniye. */
    private static final long LIVE_TTL_MS = 60_000L;

    /** Seans disi minimum cache TTL (milisaniye): 30 dakika. Gercek TTL bir sonraki seans acilisina kadar hesaplanir. */
    private static final long MIN_OFFHOURS_TTL_MS = 1_800_000L;

    /** Velzon API istemcisi. */
    private final VelzonApiClient velzonApiClient;

    /** Cache: endeks performans listesi. Volatile ile thread-safe erisim. */
    private volatile List<IndexPerformanceDto> cachedPerformances;

    /** Cache: son guncelleme zamani (epoch millis). */
    private volatile long cacheTimestamp;

    /** Cache yenileme icin kilit nesnesi. */
    private final Object cacheLock = new Object();

    /**
     * Tum BIST endekslerinin performans verilerini dondurur.
     *
     * <p>Velzon API'den {@code /api/pinescreener/velzon_indexes_performance}
     * endpoint'ini cagirarak endeks verilerini alir.</p>
     *
     * <p>Her endeks icin sembol ({@code symbol} alanindaki exchange prefix'i
     * cikartilir), son fiyat, gunluk/haftalik/aylik/ceyreklik/6 aylik/yillik
     * degisim yuzdesi bilgisi dondurulur.</p>
     *
     * <p>Sonuclar volatile cache ile saklanir. Dinamik TTL stratejisi:
     * seans saatlerinde (09:50-18:25) 60s, seans disinda bir sonraki seans acilisina kadar
     * ({@link BistCacheUtils#getDynamicOffhoursTTL(long, long)}). Cache suresi dolmadan
     * tekrar cagrilirsa cached deger dondurulur.</p>
     *
     * @return endeks performans listesi; hata durumunda bos liste
     */
    public List<IndexPerformanceDto> getIndexPerformances() {
        // Cache kontrolu (ilk kontrol — lock disinda)
        long ttl = BistCacheUtils.getDynamicOffhoursTTL(LIVE_TTL_MS, MIN_OFFHOURS_TTL_MS);
        long now = System.currentTimeMillis();
        if (cachedPerformances != null && (now - cacheTimestamp) < ttl) {
            log.debug("[INDEX-PERF] Cache hit, kalan sure: {}s",
                    (ttl - (now - cacheTimestamp)) / 1000);
            return cachedPerformances;
        }

        synchronized (cacheLock) {
            // Double-check: baska thread araya girip cache'i guncellemis olabilir
            ttl = BistCacheUtils.getDynamicOffhoursTTL(LIVE_TTL_MS, MIN_OFFHOURS_TTL_MS);
            now = System.currentTimeMillis();
            if (cachedPerformances != null && (now - cacheTimestamp) < ttl) {
                return cachedPerformances;
            }

            log.info("[INDEX-PERF] Endeks performans verileri Velzon API'den cekiliyor (TTL: {}ms)", ttl);

            try {
                Map<String, Object> response = velzonApiClient.get(
                        INDEXES_PERFORMANCE_PATH,
                        new TypeReference<Map<String, Object>>() {}
                );

                // Data dizisini parse et
                Object rawData = response.get("data");
                if (!(rawData instanceof List)) {
                    log.warn("[INDEX-PERF] Data alani beklenmeyen formatta: {}",
                            rawData != null ? rawData.getClass().getSimpleName() : "null");
                    return cachedPerformances != null ? cachedPerformances : List.of();
                }

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> data = (List<Map<String, Object>>) rawData;

                List<IndexPerformanceDto> result = new ArrayList<>();

                for (Map<String, Object> item : data) {
                    IndexPerformanceDto dto = parseItem(item);
                    if (dto != null) {
                        result.add(dto);
                    }
                }

                // Cache guncelle
                this.cachedPerformances = Collections.unmodifiableList(result);
                this.cacheTimestamp = System.currentTimeMillis();

                log.info("[INDEX-PERF] {} endeks performans verisi basariyla yuklendi", result.size());

                return cachedPerformances;

            } catch (Exception e) {
                log.error("[INDEX-PERF] Endeks performans verisi alinamadi: {}", e.getMessage(), e);
                return cachedPerformances != null ? cachedPerformances : List.of();
            }
        }
    }

    /**
     * Tek bir data satirini parse ederek {@link IndexPerformanceDto} olusturur.
     *
     * <p>Velzon pinescreener formati: her item dogrudan {@code symbol} ve
     * {@code plotN} key'leri ile gelir.</p>
     *
     * @param item tek bir veri satiri (data dizisinin elemani)
     * @return parse edilmis DTO; gecersiz veri durumunda {@code null}
     */
    private IndexPerformanceDto parseItem(Map<String, Object> item) {
        // Symbol: "BIST:XU100" → "XU100"
        String symbol = extractSymbol(item.get("symbol"));
        if (symbol == null) {
            return null;
        }

        return IndexPerformanceDto.builder()
                .symbol(symbol)
                .lastPrice(getPlotValue(item, "plot16"))
                .dailyChange(getPlotValue(item, "plot6"))
                .weeklyChange(getPlotValue(item, "plot7"))
                .monthlyChange(getPlotValue(item, "plot8"))
                .quarterlyChange(getPlotValue(item, "plot9"))
                .sixMonthChange(getPlotValue(item, "plot10"))
                .yearlyChange(getPlotValue(item, "plot12"))
                .build();
    }

    /**
     * Sembol degerini parse eder ve exchange prefix'ini cikarir.
     *
     * <p>"BIST:XFINK" → "XFINK", "FX:EURTRY" → "EURTRY" seklinde
     * iki noktadan sonrasini alir.</p>
     *
     * @param raw symbol ham degeri
     * @return temizlenmis sembol (orn. "XU100"); gecersiz veri durumunda {@code null}
     */
    private String extractSymbol(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.toString();
        int colonIdx = value.indexOf(':');
        if (colonIdx >= 0 && colonIdx < value.length() - 1) {
            return value.substring(colonIdx + 1);
        }
        return value;
    }

    /**
     * Belirtilen plot key'indeki degeri double olarak okur.
     *
     * <p>Key bulunamazsa veya deger numerik degilse 0.0 dondurur.</p>
     *
     * @param item data satiri
     * @param plotKey plot key'i (orn. "plot16")
     * @return double deger; okunamazsa 0.0
     */
    private double getPlotValue(Map<String, Object> item, String plotKey) {
        Object value = item.get(plotKey);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 0.0;
    }
}
