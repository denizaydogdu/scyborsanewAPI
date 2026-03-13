package com.scyborsa.api.repository;

import com.scyborsa.api.model.haber.HaberDetay;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Haber detay veritabani erisim katmani.
 *
 * <p>{@code haber_detay} tablosuna CRUD islemleri saglar. Headline sync,
 * detail fetch ve cleanup operasyonlari icin ozel query'ler sunar.</p>
 *
 * @see com.scyborsa.api.model.haber.HaberDetay
 * @see com.scyborsa.api.service.HaberSyncJob
 */
public interface HaberDetayRepository extends JpaRepository<HaberDetay, Long> {

    /**
     * newsId ile haber arar.
     *
     * @param newsId TradingView haber kimligi
     * @return bulunan haber veya empty
     */
    Optional<HaberDetay> findByNewsId(String newsId);

    /**
     * newsId mevcutlugunu kontrol eder (duplicate detection).
     *
     * @param newsId TradingView haber kimligi
     * @return mevcut ise {@code true}
     */
    boolean existsByNewsId(String newsId);

    /**
     * Verilen newsId listesindeki mevcut olanlari dondurur (batch duplicate check).
     *
     * @param ids kontrol edilecek newsId listesi
     * @return DB'de mevcut olan newsId'ler
     */
    @Query("SELECT h.newsId FROM HaberDetay h WHERE h.newsId IN :ids")
    Set<String> findExistingNewsIds(@Param("ids") List<String> ids);

    /**
     * Detayi henuz alinmamis haberleri olusturma sirasina gore dondurur.
     *
     * @return fetch edilmemis haberler listesi (eski -> yeni)
     */
    List<HaberDetay> findByFetchedFalseOrderByCreateTimeAsc();

    /**
     * Yayin tarihine gore sirali tum ID'leri dondurur (cleanup icin).
     *
     * @param pageable sayfalama (maxStored kadar ID tutmak icin)
     * @return saklancak haber ID listesi
     */
    @Query("SELECT h.id FROM HaberDetay h ORDER BY h.published DESC")
    List<Long> findAllIdsOrderByPublishedDesc(Pageable pageable);

    /**
     * Belirtilen ID listesi disindaki haberleri siler.
     *
     * @param ids saklanacak haber ID'leri
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM HaberDetay h WHERE h.id NOT IN :ids")
    void deleteByIdNotIn(@Param("ids") List<Long> ids);

    /**
     * Son N haberi tutup gerisini siler (tek native sorgu).
     *
     * @param maxStored saklanacak maksimum haber sayisi
     */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM haber_detay WHERE id NOT IN " +
            "(SELECT id FROM haber_detay ORDER BY COALESCE(published, create_time) DESC LIMIT :maxStored)",
            nativeQuery = true)
    void cleanupOldNews(@Param("maxStored") int maxStored);
}
