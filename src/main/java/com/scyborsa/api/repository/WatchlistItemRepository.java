package com.scyborsa.api.repository;

import com.scyborsa.api.model.WatchlistItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * Takip listesi hisse kalemi repository interface'i.
 *
 * <p>{@link WatchlistItem} entity'si icin veritabani erisim katmani.
 * Hisse ekleme/cikarma, siralama guncelleme ve toplu sorgu islemlerini destekler.</p>
 *
 * @see WatchlistItem
 * @see com.scyborsa.api.service.watchlist.WatchlistService
 */
public interface WatchlistItemRepository extends JpaRepository<WatchlistItem, Long> {

    /**
     * Belirtilen takip listesindeki hisse kalemlerini siralama degerine gore artan sirada getirir.
     *
     * @param watchlistId takip listesi ID'si
     * @return sirali hisse kalemi listesi
     */
    List<WatchlistItem> findByWatchlistIdOrderByDisplayOrderAsc(Long watchlistId);

    /**
     * Belirtilen takip listesindeki hisse kalemi sayisini doner.
     *
     * @param watchlistId takip listesi ID'si
     * @return hisse kalemi sayisi
     */
    long countByWatchlistId(Long watchlistId);

    /**
     * Belirtilen takip listesinde ayni hisse kodu var mi kontrol eder.
     *
     * @param watchlistId takip listesi ID'si
     * @param stockCode   hisse kodu
     * @return mevcutsa {@code true}
     */
    boolean existsByWatchlistIdAndStockCode(Long watchlistId, String stockCode);

    /**
     * Belirtilen takip listesinden belirtilen hisse kodunu siler.
     *
     * @param watchlistId takip listesi ID'si
     * @param stockCode   silinecek hisse kodu
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    void deleteByWatchlistIdAndStockCode(Long watchlistId, String stockCode);

    /**
     * Tum aktif takip listelerindeki benzersiz hisse kodlarini doner.
     * Broadcast servisi icin — hangi hisselerin izlenmesi gerektigini belirler.
     *
     * @return benzersiz hisse kodu kumesi
     */
    @Query("SELECT DISTINCT wi.stockCode FROM WatchlistItem wi JOIN wi.watchlist w JOIN w.user u WHERE w.aktif = true AND u.aktif = true")
    Set<String> findAllDistinctActiveStockCodes();

    /**
     * Belirtilen hisse kodunu takip eden kullanicilarin email adreslerini doner.
     * Broadcast servisi icin — hisse fiyat guncellemesinin kimlere gonderilecegini belirler.
     *
     * @param stockCode hisse kodu
     * @return kullanici email kumesi
     */
    @Query("SELECT DISTINCT w.userEmail FROM WatchlistItem wi JOIN wi.watchlist w JOIN w.user u WHERE wi.stockCode = :stockCode AND w.aktif = true AND u.aktif = true")
    Set<String> findUserEmailsByStockCode(@Param("stockCode") String stockCode);

    /**
     * Belirtilen hisse kaleminin siralama degerini gunceller.
     *
     * @param id    hisse kalemi ID'si
     * @param order yeni siralama degeri
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE WatchlistItem wi SET wi.displayOrder = :order WHERE wi.id = :id")
    void updateDisplayOrder(@Param("id") Long id, @Param("order") int order);

    /**
     * Belirtilen hisse kodunun, belirtilen kullanicinin baska aktif takip listelerinde olup olmadigini kontrol eder.
     *
     * @param stockCode          hisse kodu
     * @param userId             kullanici ID'si
     * @param excludeWatchlistId haric tutulacak takip listesi ID'si
     * @return baska aktif listede mevcutsa {@code true}
     */
    @Query("SELECT COUNT(wi) > 0 FROM WatchlistItem wi JOIN wi.watchlist w WHERE wi.stockCode = :stockCode AND w.user.id = :userId AND w.aktif = true AND w.id <> :excludeWatchlistId")
    boolean existsByStockCodeInOtherActiveWatchlists(@Param("stockCode") String stockCode, @Param("userId") Long userId, @Param("excludeWatchlistId") Long excludeWatchlistId);
}
