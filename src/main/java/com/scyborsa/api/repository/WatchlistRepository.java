package com.scyborsa.api.repository;

import com.scyborsa.api.model.Watchlist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Takip listesi repository interface'i.
 *
 * <p>{@link Watchlist} entity'si icin veritabani erisim katmani.
 * Kullanici bazli sorgular, varsayilan liste kontrolu ve siralama islemlerini destekler.</p>
 *
 * @see Watchlist
 * @see com.scyborsa.api.service.watchlist.WatchlistService
 */
public interface WatchlistRepository extends JpaRepository<Watchlist, Long> {

    /**
     * Belirtilen kullanicinin aktif takip listelerini siralama degerine gore artan sirada getirir.
     *
     * @param userId kullanici ID'si
     * @return aktif takip listesi listesi
     */
    List<Watchlist> findByUserIdAndAktifTrueOrderByDisplayOrderAsc(Long userId);

    /**
     * Belirtilen kullanicinin aktif takip listesi sayisini doner.
     *
     * @param userId kullanici ID'si
     * @return aktif takip listesi sayisi
     */
    long countByUserIdAndAktifTrue(Long userId);

    /**
     * Belirtilen kullanicinin varsayilan aktif takip listesini getirir.
     *
     * @param userId kullanici ID'si
     * @return varsayilan takip listesi (varsa)
     */
    Optional<Watchlist> findByUserIdAndIsDefaultTrueAndAktifTrue(Long userId);

    /**
     * Belirtilen kullanicida ayni isimde aktif takip listesi var mi kontrol eder (buyuk-kucuk harf duyarsiz).
     *
     * @param userId kullanici ID'si
     * @param name   liste adi
     * @return mevcutsa {@code true}
     */
    boolean existsByUserIdAndNameIgnoreCaseAndAktifTrue(Long userId, String name);

    /**
     * Belirtilen kullanicinin aktif takip listelerindeki en buyuk displayOrder degerini doner.
     * Hic liste yoksa 0 doner.
     *
     * @param userId kullanici ID'si
     * @return en buyuk displayOrder degeri
     */
    @Query("SELECT COALESCE(MAX(w.displayOrder), 0) FROM Watchlist w WHERE w.user.id = :userId AND w.aktif = true")
    int findMaxDisplayOrderByUserId(@Param("userId") Long userId);

    /**
     * Kullanicinin aktif takip listelerinde belirtilen ismin mevcut olup olmadigini kontrol eder (belirtilen ID haric).
     *
     * @param userId    kullanici kimligi
     * @param name      kontrol edilecek liste adi
     * @param excludeId haric tutulacak liste kimligi
     * @return isim mevcutsa {@code true}
     */
    @Query("SELECT COUNT(w) > 0 FROM Watchlist w WHERE w.user.id = :userId AND LOWER(w.name) = LOWER(:name) AND w.aktif = true AND w.id <> :excludeId")
    boolean existsByUserIdAndNameIgnoreCaseAndAktifTrueExcluding(@Param("userId") Long userId, @Param("name") String name, @Param("excludeId") Long excludeId);
}
