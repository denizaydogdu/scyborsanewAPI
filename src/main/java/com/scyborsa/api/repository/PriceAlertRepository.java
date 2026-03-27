package com.scyborsa.api.repository;

import com.scyborsa.api.enums.AlertDirection;
import com.scyborsa.api.enums.AlertStatus;
import com.scyborsa.api.model.PriceAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Fiyat alarmi repository interface'i.
 *
 * <p>{@link PriceAlert} entity'si icin veritabani erisim katmani.
 * Kullanici bazli sorgular, durum filtreleme ve toplu guncelleme islemlerini destekler.</p>
 *
 * @see PriceAlert
 * @see com.scyborsa.api.service.alert.PriceAlertService
 */
public interface PriceAlertRepository extends JpaRepository<PriceAlert, Long> {

    /**
     * Belirtilen kullanici ve alarm durumuna gore alarmlari getirir (olusturma zamanina gore azalan).
     *
     * @param userId kullanici ID'si
     * @param status alarm durumu
     * @return filtrelenmis alarm listesi
     */
    List<PriceAlert> findByUserIdAndStatusOrderByCreateTimeDesc(Long userId, AlertStatus status);

    /**
     * Belirtilen kullanicinin tum alarmlarini getirir (olusturma zamanina gore azalan).
     *
     * @param userId kullanici ID'si
     * @return tum alarm listesi
     */
    List<PriceAlert> findByUserIdOrderByCreateTimeDesc(Long userId);

    /**
     * Belirtilen durum ve hisse koduna gore alarmlari getirir (alarm motoru icin).
     *
     * @param status    alarm durumu
     * @param stockCode hisse kodu
     * @return eslesen alarm listesi
     */
    List<PriceAlert> findByStatusAndStockCode(AlertStatus status, String stockCode);

    /**
     * Belirtilen durumdaki tum alarmlari getirir (alarm motoru baslangici icin).
     *
     * @param status alarm durumu
     * @return eslesen alarm listesi
     */
    List<PriceAlert> findByStatus(AlertStatus status);

    /**
     * Kullanicinin okunmamis tetiklenmis alarm sayisini doner.
     *
     * @param userId kullanici ID'si
     * @param status alarm durumu (genellikle TRIGGERED)
     * @return okunmamis alarm sayisi
     */
    long countByUserIdAndStatusAndReadAtIsNull(Long userId, AlertStatus status);

    /**
     * Kullanicinin belirtilen durumdaki alarm sayisini doner.
     *
     * @param userId kullanici ID'si
     * @param status alarm durumu
     * @return alarm sayisi
     */
    long countByUserIdAndStatus(Long userId, AlertStatus status);

    /**
     * Ayni kullanici, hisse, yon, hedef fiyat ve durumda alarm var mi kontrol eder (duplikat engeli).
     *
     * @param userId      kullanici ID'si
     * @param stockCode   hisse kodu
     * @param direction   alarm yonu
     * @param targetPrice hedef fiyat
     * @param status      alarm durumu
     * @return mevcutsa {@code true}
     */
    boolean existsByUserIdAndStockCodeAndDirectionAndTargetPriceAndStatus(Long userId, String stockCode,
                                                                          AlertDirection direction, Double targetPrice,
                                                                          AlertStatus status);

    /**
     * Kullanicinin tum okunmamis tetiklenmis alarmlarini toplu olarak okundu isaretler.
     *
     * @param userId kullanici ID'si
     * @param readAt okunma zamani
     * @return guncellenen kayit sayisi
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE PriceAlert a SET a.readAt = :readAt WHERE a.user.id = :userId AND a.status = :status AND a.readAt IS NULL")
    int markAllRead(@Param("userId") Long userId, @Param("readAt") LocalDateTime readAt, @Param("status") AlertStatus status);

    /**
     * Tum alarmlari olusturma zamanina gore azalan sirada getirir (admin panel icin).
     *
     * @return tum alarm listesi
     */
    List<PriceAlert> findAllByOrderByCreateTimeDesc();

    /**
     * Belirtilen durumdaki tum alarmlari olusturma zamanina gore azalan sirada getirir (admin panel icin).
     *
     * @param status alarm durumu filtresi
     * @return filtrelenmis alarm listesi
     */
    List<PriceAlert> findByStatusOrderByCreateTimeDesc(AlertStatus status);
}
