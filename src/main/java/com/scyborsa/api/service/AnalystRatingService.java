package com.scyborsa.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.scyborsa.api.dto.analyst.AnalystRatingDto;
import com.scyborsa.api.dto.analyst.FintablesAnalystRatingResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fintables analist tavsiye verilerini saglayan servis.
 *
 * <p>{@link FintablesApiClient} kullanarak Fintables API'den
 * analist tavsiyelerini ceker ve volatile cache ile saklar
 * (varsayilan TTL: 24 saat / 86400 saniye, double-check locking).</p>
 *
 * @see FintablesApiClient
 * @see AnalystRatingDto
 */
@Slf4j
@Service
public class AnalystRatingService {

    private final FintablesApiClient fintablesApiClient;
    private final AraciKurumService araciKurumService;

    /** Cache: analist tavsiyeleri. */
    private volatile List<AnalystRatingDto> cachedRatings;

    /** Cache: son guncelleme zamani (epoch millis). */
    private volatile long cacheTimestamp;

    /** Cache kilit nesnesi. */
    private final Object cacheLock = new Object();

    /** Cache TTL (saniye cinsinden). */
    @Value("${analyst-rating.cache.ttl-seconds:86400}")
    private int cacheTtlSeconds;

    /**
     * Constructor injection ile bagimliliklari alir.
     *
     * @param fintablesApiClient Fintables API istemcisi
     * @param araciKurumService araci kurum sync servisi
     */
    public AnalystRatingService(FintablesApiClient fintablesApiClient, AraciKurumService araciKurumService) {
        this.fintablesApiClient = fintablesApiClient;
        this.araciKurumService = araciKurumService;
    }

    /**
     * Tum analist tavsiyelerini dondurur.
     *
     * <p>Fintables API'den paginated olarak tum tavsiyeleri ceker.
     * Sonuclar volatile cache ile saklanir (TTL: 24 saat).</p>
     *
     * @return analist tavsiye listesi; hata durumunda bos liste
     */
    public List<AnalystRatingDto> getAnalystRatings() {
        long now = System.currentTimeMillis();
        if (cachedRatings != null && (now - cacheTimestamp) < (long) cacheTtlSeconds * 1000) {
            return cachedRatings;
        }

        List<AnalystRatingDto> freshRatings = null;
        synchronized (cacheLock) {
            now = System.currentTimeMillis();
            if (cachedRatings != null && (now - cacheTimestamp) < (long) cacheTtlSeconds * 1000) {
                return cachedRatings;
            }

            log.info("[ANALYST-RATING] Analist tavsiyeleri Fintables API'den cekiliyor");
            try {
                List<AnalystRatingDto> allRatings = fetchAllPages();

                if (allRatings.isEmpty()) {
                    log.warn("[ANALYST-RATING] API bos sonuc dondurdu");
                    return cachedRatings != null ? cachedRatings : List.of();
                }

                this.cachedRatings = Collections.unmodifiableList(allRatings);
                this.cacheTimestamp = System.currentTimeMillis();
                freshRatings = this.cachedRatings;
                log.info("[ANALYST-RATING] {} tavsiye cache'lendi", allRatings.size());
            } catch (Exception e) {
                log.error("[ANALYST-RATING] API hatasi: {}", e.getMessage());
                return cachedRatings != null ? cachedRatings : List.of();
            }
        }

        // Araci kurum sync lock disinda — concurrent reader'lari bloklamaz
        if (freshRatings != null) {
            try {
                araciKurumService.syncFromAnalystRatings(freshRatings);
            } catch (Exception e) {
                log.warn("Araci kurum sync basarisiz, cache calismaya devam ediyor: {}", e.getMessage());
            }
            return freshRatings;
        }

        return cachedRatings != null ? cachedRatings : List.of();
    }

    /**
     * Fintables API'den tum sayfalari cekerek birlestir.
     *
     * <p>Pagination: {@code ?limit=100&offset=0}, {@code next} URL null olana kadar devam eder.</p>
     *
     * @return tum sayfalardaki tavsiye listesi
     * @throws Exception API veya parse hatasi durumunda
     */
    private List<AnalystRatingDto> fetchAllPages() throws Exception {
        List<AnalystRatingDto> allResults = new ArrayList<>();
        String url = "/analyst-ratings/?limit=100&offset=0";
        int maxPages = 50;
        int pageCount = 0;

        while (url != null && pageCount < maxPages) {
            FintablesAnalystRatingResponse response = fintablesApiClient.get(
                    url, new TypeReference<FintablesAnalystRatingResponse>() {}
            );

            if (response.getResults() != null) {
                allResults.addAll(response.getResults());
            }

            url = response.getNext();
            pageCount++;
            log.debug("[ANALYST-RATING] Sayfa cekildi: {} tavsiye (toplam: {})",
                    response.getResults() != null ? response.getResults().size() : 0,
                    allResults.size());
        }

        if (pageCount >= maxPages) {
            log.warn("[ANALYST-RATING] Pagination guvenlik limiti asildi ({})", maxPages);
        }

        return allResults;
    }
}
