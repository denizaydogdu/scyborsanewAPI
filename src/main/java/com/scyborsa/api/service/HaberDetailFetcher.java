package com.scyborsa.api.service;

import com.scyborsa.api.model.HaberDetay;
import com.scyborsa.api.repository.HaberDetayRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Haber detay cekme islemlerini yuruten yardimci bean.
 *
 * <p>{@link HaberSyncJob} icinde self-call ile {@code @Transactional} proxy bypass sorununu
 * cozmek icin ayri bir bean olarak cikarilmistir. Spring AOP proxy'si ancak
 * farkli bean'ler arasi cagrilarda devreye girer.</p>
 *
 * @see HaberSyncJob
 * @see HaberDetailService
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HaberDetailFetcher {

    private final HaberDetayRepository haberDetayRepository;
    private final HaberDetailService haberDetailService;

    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");

    /**
     * Tek haberin detayini fetch eder ve DB'ye kaydeder.
     *
     * <p>KAP haberleri icin Jsoup scraping, piyasa/dunya haberleri icin /v3/story API kullanilir.</p>
     *
     * @param haber detayi cekilecek haber entity'si
     */
    @Transactional
    public void fetchSingleDetail(HaberDetay haber) {
        if ("KAP".equals(haber.getNewsType()) && haber.getStoryPath() != null) {
            // KAP: Jsoup scraping
            String[] result = haberDetailService.scrapeKapDetail(haber.getStoryPath());
            if (result != null && result.length >= 2) {
                haber.setDetailContent(result[0]);
                haber.setOriginalKapUrl(result[1]);
            } else {
                log.warn("scrapeKapDetail beklenmeyen sonuc [newsId={}]", haber.getNewsId());
            }
        } else {
            // Market/World: /v3/story API
            String[] result = haberDetailService.fetchStoryDetail(haber.getNewsId());
            if (result != null && result.length >= 2) {
                haber.setShortDescription(result[0]);
                haber.setDetailContent(result[1]);
            } else {
                log.warn("fetchStoryDetail beklenmeyen sonuc [newsId={}]", haber.getNewsId());
            }
        }

        haber.setFetched(true);
        haber.setFetchedAt(LocalDateTime.now(ISTANBUL));
        haberDetayRepository.save(haber);
    }

    /**
     * Tek haberin detayini on-demand olarak ceker (API endpoint icin).
     *
     * <p>Detay zaten cekilmisse islem yapmaz. Controller tarafindan
     * kullanicinin haber detay sayfasini goruntulemesinde cagirilir.</p>
     *
     * @param haber detayi cekilecek haber entity'si
     */
    @Transactional
    public void fetchDetailOnDemand(HaberDetay haber) {
        HaberDetay current = haberDetayRepository.findById(haber.getId()).orElse(haber);
        if (current.isFetched()) return;
        fetchSingleDetail(current);
    }
}
