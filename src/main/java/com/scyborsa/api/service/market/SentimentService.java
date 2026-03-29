package com.scyborsa.api.service.market;

import com.scyborsa.api.service.client.VelzonApiClient;
import com.scyborsa.api.utils.BistCacheUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.scyborsa.api.dto.market.DashboardSentimentDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Piyasa sentiment (duyarlilik) verisini hesaplayan servis.
 *
 * <p>Velzon API uzerinden BIST hisselerinin performans verilerini ceker
 * ve kisa/orta/uzun vadeli yukselis oranlarini hesaplar.</p>
 *
 * <p>Veri kaynagi: {@code /api/pinescreener/velzon_performance} endpoint'i.
 * Her hisse icin {@code plot17} (kisa vadeli), {@code plot18} (orta vadeli),
 * {@code plot19} (uzun vadeli) alanlari 1 ise yukseliste kabul edilir.</p>
 *
 * <p>Cache stratejisi: Seans saatlerinde 60 saniye, seans disinda bir sonraki seans acilisina kadar
 * ({@link BistCacheUtils#getDynamicOffhoursTTL(long, long)}).</p>
 *
 * @see VelzonApiClient
 * @see DashboardSentimentDto
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SentimentService {

    /** Seans ici cache TTL (milisaniye): 60 saniye. */
    private static final long LIVE_TTL_MS = 60_000L;

    /** Seans disi minimum cache TTL (milisaniye): 30 dakika. Gercek TTL bir sonraki seans acilisina kadar hesaplanir. */
    private static final long MIN_OFFHOURS_TTL_MS = 1_800_000L;

    /** Soguk baslatma hatasi sonrasi minimum yeniden deneme gecikmesi (milisaniye): 30 saniye. */
    private static final long COLD_START_RETRY_MS = 30_000L;

    /** Velzon API istemcisi. */
    private final VelzonApiClient velzonApiClient;

    /** Cache'lenmis sentiment verisi. */
    private volatile DashboardSentimentDto cachedSentiment;

    /** Cache'in son guncellenme zamani (epoch ms). */
    private volatile long cacheTimestamp;

    /** Cache yenileme icin kilit nesnesi. */
    private final Object cacheLock = new Object();

    /**
     * Piyasa sentiment verisini dondurur (cache'den).
     *
     * <p>Cache suresi dolduysa Velzon API'den yeni veri ceker.
     * Seans saatlerinde 60s, seans disinda 30dk TTL uygulanir.</p>
     *
     * @return piyasa sentiment verisini iceren {@link DashboardSentimentDto}
     */
    public DashboardSentimentDto getSentimentData() {
        refreshCacheIfStale();
        DashboardSentimentDto sentiment = cachedSentiment;
        return sentiment != null ? sentiment : buildEmptyDto();
    }

    /**
     * Cache suresi dolduysa Velzon API'den yeni veri ceker.
     * Double-check locking ile thread-safe cache yenileme.
     */
    private void refreshCacheIfStale() {
        long ttl = BistCacheUtils.getDynamicOffhoursTTL(LIVE_TTL_MS, MIN_OFFHOURS_TTL_MS);
        long now = System.currentTimeMillis();

        if (cachedSentiment != null && (now - cacheTimestamp) < ttl) {
            return;
        }

        synchronized (cacheLock) {
            ttl = BistCacheUtils.getDynamicOffhoursTTL(LIVE_TTL_MS, MIN_OFFHOURS_TTL_MS);
            now = System.currentTimeMillis();
            if (cachedSentiment != null && (now - cacheTimestamp) < ttl) {
                return;
            }

            log.info("[SENTIMENT] Cache yenileniyor (TTL: {}ms)", ttl);
            DashboardSentimentDto freshData = fetchSentimentFromApi();

            if (freshData != null && freshData.getToplamHisse() > 0) {
                this.cachedSentiment = freshData;
                this.cacheTimestamp = System.currentTimeMillis();
                log.info("[SENTIMENT] Cache guncellendi: {} hisse", freshData.getToplamHisse());
            } else {
                if (cachedSentiment != null) {
                    // Mevcut cache var — tam TTL backoff (retry storm engeli)
                    this.cacheTimestamp = System.currentTimeMillis();
                    log.warn("[SENTIMENT] Veri cekilemedi, mevcut cache korunuyor. TTL sonrasi yeniden denenecek.");
                } else {
                    // Soguk baslatma hatasi — 30 saniye sonra yeniden dene
                    this.cacheTimestamp = System.currentTimeMillis() - ttl + COLD_START_RETRY_MS;
                    log.warn("[SENTIMENT] Soguk baslatmada veri cekilemedi, 30 saniye sonra yeniden denenecek.");
                }
            }
        }
    }

    /**
     * Velzon API'den sentiment verisini ceker ve hesaplar.
     *
     * <p>Velzon API'den performans verisini ceker, {@code data} dizisindeki
     * her eleman icin {@code plot17}, {@code plot18}, {@code plot19} degerlerini
     * kontrol eder. Degeri 1 olan hisseler yukseliste kabul edilir ve
     * toplam hisse sayisina oranlanarak yuzde hesaplanir.</p>
     *
     * @return piyasa sentiment DTO'su; hata durumunda {@code null}
     */
    private DashboardSentimentDto fetchSentimentFromApi() {
        try {
            Map<String, Object> response = velzonApiClient.get(
                    "/api/pinescreener/velzon_performance",
                    new TypeReference<Map<String, Object>>() {}
            );

            Object rawData = response.get("data");
            if (!(rawData instanceof List)) {
                log.warn("Sentiment verisi beklenmeyen formatta: {}",
                        rawData != null ? rawData.getClass().getSimpleName() : "null");
                return null;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) rawData;

            if (data.isEmpty()) {
                log.warn("Sentiment verisi bos dondu");
                return null;
            }

            int total = data.size();
            int kisaCount = 0;
            int ortaCount = 0;
            int uzunCount = 0;

            for (Map<String, Object> item : data) {
                if (isPlotPositive(item, "plot17")) kisaCount++;
                if (isPlotPositive(item, "plot18")) ortaCount++;
                if (isPlotPositive(item, "plot19")) uzunCount++;
            }

            double kisaVadeli = Math.round(kisaCount * 1000.0 / total) / 10.0;
            double ortaVadeli = Math.round(ortaCount * 1000.0 / total) / 10.0;
            double uzunVadeli = Math.round(uzunCount * 1000.0 / total) / 10.0;

            return DashboardSentimentDto.builder()
                    .kisaVadeli(kisaVadeli)
                    .ortaVadeli(ortaVadeli)
                    .uzunVadeli(uzunVadeli)
                    .toplamHisse(total)
                    .timestamp(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                    .build();

        } catch (Exception e) {
            log.error("Sentiment verisi alinamadi: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Belirtilen plot alaninin degerinin 1 (pozitif/yukselis) olup olmadigini kontrol eder.
     *
     * <p>Alan degeri {@link Number} tipinde ise {@code intValue() == 1} kontrolu yapilir.</p>
     *
     * @param item     veri satiri (hisse verisi map'i)
     * @param plotKey  kontrol edilecek alan adi (ör. "plot17")
     * @return alan degeri 1 ise {@code true}, aksi halde {@code false}
     */
    private boolean isPlotPositive(Map<String, Object> item, String plotKey) {
        Object value = item.get(plotKey);
        if (value instanceof Number) {
            return ((Number) value).intValue() == 1;
        }
        return false;
    }

    /**
     * Tum degerleri 0.0 olan bos bir sentiment DTO'su olusturur.
     *
     * <p>Hata durumlarinda veya veri alinamadiginda kullanilir (graceful degradation).</p>
     *
     * @return tum alanlari sifir olan {@link DashboardSentimentDto}
     */
    private DashboardSentimentDto buildEmptyDto() {
        return DashboardSentimentDto.builder()
                .kisaVadeli(0.0)
                .ortaVadeli(0.0)
                .uzunVadeli(0.0)
                .toplamHisse(0)
                .timestamp(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build();
    }
}
