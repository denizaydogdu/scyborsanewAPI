package com.scyborsa.api.service;

import com.scyborsa.api.dto.kap.KapHaberDto;
import com.scyborsa.api.repository.KapHaberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * KAP (Kamuyu Aydinlatma Platformu) haberlerini sunan servis.
 *
 * <p>DB'deki son 10 KAP haberini bellek icerisinde cache'leyerek sunar.
 * Cache TTL suresi {@code kap.cache.ttl-minutes} property'si ile konfigüre edilir (varsayilan 10dk).</p>
 *
 * <p>Bagimliliklar:</p>
 * <ul>
 *   <li>{@link com.scyborsa.api.repository.KapHaberRepository} - KAP haber veritabani erisimi</li>
 * </ul>
 *
 * @see com.scyborsa.api.dto.kap.KapHaberDto
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KapHaberService {

    private final KapHaberRepository kapHaberRepository;

    @Value("${kap.cache.ttl-minutes:10}")
    private int cacheTtlMinutes;

    private volatile List<KapHaberDto> cachedData = List.of();
    private volatile long lastFetchTime = 0;

    /**
     * Son 10 KAP haberini döner.
     *
     * <p>Cache süresi dolmamissa bellekteki listeyi döner.
     * Süre dolduysa DB'den taze veri çeker ve cache'i yeniler.</p>
     *
     * @return en güncel 10 KAP haberi listesi; veri yoksa bos liste
     */
    public synchronized List<KapHaberDto> getLatestKapHaberler() {
        long now = System.currentTimeMillis();
        long ttlMillis = cacheTtlMinutes * 60_000L;

        if (now - lastFetchTime < ttlMillis && !cachedData.isEmpty()) {
            return cachedData;
        }

        log.info("[KAP-CACHE] Cache expired (ttl={}dk), DB'den çekiliyor...", cacheTtlMinutes);
        List<KapHaberDto> fresh = kapHaberRepository.findTop10ByOrderBySentAtDesc().stream()
                .map(k -> KapHaberDto.builder()
                        .id(k.getId())
                        .companyCode(k.getCompanyCode())
                        .title(k.getTitle())
                        .subject(k.getSubject())
                        .summary(k.getSummary())
                        .publishedAt(k.getPublishedAt())
                        .build())
                .toList();

        cachedData = fresh;
        lastFetchTime = now;
        log.info("[KAP-CACHE] {} kayıt cache'lendi, sonraki yenileme {}dk sonra", fresh.size(), cacheTtlMinutes);
        return fresh;
    }
}
