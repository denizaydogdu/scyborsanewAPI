package com.scyborsa.api.repository;

import com.scyborsa.api.enums.ScreenerTypeEnum;
import com.scyborsa.api.model.ScreenerResultModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Tarama sonuçları için JPA repository'si.
 *
 * <p>{@link ScreenerResultModel} entity'si üzerinde CRUD işlemleri, sliding-window
 * ortak hisse sorguları ve TP/SL bulk güncelleme yeteneği sağlar.</p>
 *
 * <p>Eski projedeki 100+ hardcoded sliding-window method'u tek parametreli
 * {@link #findCommonStocksInTimeWindow} ile değiştirilmiştir.</p>
 *
 * @see ScreenerResultModel
 */
@Repository
public interface ScreenerResultRepository extends JpaRepository<ScreenerResultModel, Long> {

    /**
     * Belirli bir günün tüm tarama sonuçlarını getirir.
     *
     * @param screenerDay tarama günü
     * @return tarama sonuçları listesi
     */
    List<ScreenerResultModel> findByScreenerDay(LocalDate screenerDay);

    /**
     * Belirli bir gunun toplam tarama sonucu sayisini dondurur.
     *
     * @param screenerDay tarama gunu
     * @return tarama sonucu sayisi
     */
    long countByScreenerDay(LocalDate screenerDay);

    /**
     * Belirli bir gunde Telegram'a gonderilen tarama sonucu sayisini dondurur.
     *
     * @param screenerDay tarama gunu
     * @return gonderilmis tarama sayisi
     */
    long countByScreenerDayAndTelegramSentTrue(LocalDate screenerDay);

    /**
     * Belirli bir gunde Telegram'a gonderilmemis tarama sonucu sayisini dondurur.
     *
     * @param screenerDay tarama gunu
     * @return gonderilmemis tarama sayisi
     */
    @Query("SELECT COUNT(sr) FROM ScreenerResultModel sr " +
           "WHERE sr.screenerDay = :screenerDay " +
           "AND (sr.telegramSent = false OR sr.telegramSent IS NULL)")
    long countTodayUnsent(@Param("screenerDay") LocalDate screenerDay);

    /**
     * TP/SL kontrolu bekleyen tarama sonucu sayisini dondurur.
     *
     * @param startTime bu tarihten sonra olusturulan kayitlar
     * @return bekleyen TP/SL kontrol sayisi
     */
    @Query("SELECT COUNT(sr) FROM ScreenerResultModel sr " +
           "WHERE sr.createTime >= :startTime " +
           "AND (((sr.tpCheckDone = false OR sr.tpCheckDone IS NULL) AND sr.tpPrice IS NOT NULL) " +
           "  OR ((sr.slCheckDone = false OR sr.slCheckDone IS NULL) AND sr.slPrice IS NOT NULL))")
    long countPendingTpSlChecks(@Param("startTime") LocalDateTime startTime);

    /**
     * Belirli bir hissenin belirli bir gündeki tarama sonuçlarını getirir.
     *
     * @param stockName hisse kodu
     * @param screenerDay tarama günü
     * @return tarama sonuçları listesi
     */
    List<ScreenerResultModel> findByStockNameAndScreenerDay(String stockName, LocalDate screenerDay);

    /**
     * Aynı hisse + gün + zaman + screenerName kombinasyonunun var olup olmadığını kontrol eder.
     * Duplicate detection için kullanılır.
     *
     * @param stockName hisse kodu
     * @param screenerDay tarama günü
     * @param screenerTime tarama zamanı
     * @param screenerName tarama adı
     * @return {@code true} ise zaten kayıtlı
     */
    boolean existsByStockNameAndScreenerDayAndScreenerTimeAndScreenerName(
            String stockName, LocalDate screenerDay, LocalTime screenerTime, String screenerName);

    /**
     * Belirli bir zaman penceresi içinde birden fazla zaman diliminde çıkan hisseleri bulur.
     *
     * <p>Eski projedeki 100+ hardcoded sliding-window method'unun tek parametreli karşılığı.
     * Aynı gün+tür kombinasyonunda, verilen zaman dilimlerinden en az {@code minDistinctCount}
     * tanesinde çıkan ve değişim yüzdesi {@code maxPercentage} altında olan hisseleri döndürür.</p>
     *
     * @param screenerDay tarama günü
     * @param screenerType tarama türü
     * @param screenerTimes kontrol edilecek zaman dilimleri
     * @param maxPercentage maksimum değişim yüzdesi filtresi
     * @param minDistinctCount minimum farklı zaman dilimi sayısı
     * @return koşulları sağlayan hisse kodları
     */
    @Query("SELECT sr.stockName FROM ScreenerResultModel sr " +
           "WHERE sr.screenerDay = :screenerDay " +
           "AND sr.screenerType = :screenerType " +
           "AND sr.screenerTime IN (:screenerTimes) " +
           "AND sr.percentage < :maxPercentage " +
           "GROUP BY sr.stockName " +
           "HAVING COUNT(DISTINCT sr.screenerTime) >= :minDistinctCount")
    List<String> findCommonStocksInTimeWindow(
            @Param("screenerDay") LocalDate screenerDay,
            @Param("screenerType") ScreenerTypeEnum screenerType,
            @Param("screenerTimes") List<LocalTime> screenerTimes,
            @Param("maxPercentage") double maxPercentage,
            @Param("minDistinctCount") long minDistinctCount);

    /**
     * {@link #findCommonStocksInTimeWindow} için güvenli sarmalayıcı.
     * Boş zaman listesi geçilirse boş liste döndürür (IN () SQL hatası önlenir).
     *
     * @param screenerDay tarama günü
     * @param screenerType tarama türü
     * @param screenerTimes kontrol edilecek zaman dilimleri
     * @param maxPercentage maksimum değişim yüzdesi filtresi
     * @param minDistinctCount minimum farklı zaman dilimi sayısı
     * @return koşulları sağlayan hisse kodları; boş liste ise boş döner
     */
    default List<String> findCommonStocksInTimeWindowSafe(
            LocalDate screenerDay,
            ScreenerTypeEnum screenerType,
            List<LocalTime> screenerTimes,
            double maxPercentage,
            long minDistinctCount) {
        if (screenerTimes == null || screenerTimes.isEmpty()) {
            return List.of();
        }
        return findCommonStocksInTimeWindow(screenerDay, screenerType,
                screenerTimes, maxPercentage, minDistinctCount);
    }

    /**
     * Henüz TP/SL kontrolü tamamlanmamış ve TP/SL fiyatı set edilmiş kayıtları getirir.
     *
     * @param startTime bu tarihten sonra oluşturulan kayıtlar
     * @return bekleyen TP/SL kontrol listesi
     */
    @Query("SELECT sr FROM ScreenerResultModel sr " +
           "WHERE sr.createTime >= :startTime " +
           "AND (((sr.tpCheckDone = false OR sr.tpCheckDone IS NULL) AND sr.tpPrice IS NOT NULL) " +
           "  OR ((sr.slCheckDone = false OR sr.slCheckDone IS NULL) AND sr.slPrice IS NOT NULL))")
    List<ScreenerResultModel> findPendingTpSlChecks(@Param("startTime") LocalDateTime startTime);

    /**
     * Belirli bir günün gün sonu verisi henüz atanmamış kayıtlarını getirir.
     *
     * @param screenerDay tarama günü
     * @return gün sonu verisi eksik olan kayıtlar
     */
    @Query("SELECT sr FROM ScreenerResultModel sr " +
           "WHERE sr.screenerDay = :screenerDay AND sr.gunSonuFiyati IS NULL")
    List<ScreenerResultModel> findByScreenerDayWithoutGunSonu(@Param("screenerDay") LocalDate screenerDay);

    /**
     * Belirtilen kayıtların TP kontrolünü toplu olarak tamamlar.
     *
     * @param ids güncellenecek kayıt id'leri
     * @param hitPrice TP tetikleme fiyatı
     * @param hitTime TP tetikleme zamanı
     * @return güncellenen kayıt sayısı
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ScreenerResultModel sr SET sr.tpCheckDone = true, " +
           "sr.tpHitPrice = :hitPrice, sr.tpHitTime = :hitTime, sr.triggerTime = :hitTime WHERE sr.id IN (:ids)")
    int bulkUpdateTpTriggered(@Param("ids") List<Long> ids,
                              @Param("hitPrice") Double hitPrice,
                              @Param("hitTime") LocalDateTime hitTime);

    /**
     * Belirtilen kayıtların SL kontrolünü toplu olarak tamamlar.
     *
     * @param ids güncellenecek kayıt id'leri
     * @param hitPrice SL tetikleme fiyatı
     * @param hitTime SL tetikleme zamanı
     * @return güncellenen kayıt sayısı
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ScreenerResultModel sr SET sr.slCheckDone = true, " +
           "sr.slHitPrice = :hitPrice, sr.slHitTime = :hitTime, sr.triggerTime = :hitTime WHERE sr.id IN (:ids)")
    int bulkUpdateSlTriggered(@Param("ids") List<Long> ids,
                              @Param("hitPrice") Double hitPrice,
                              @Param("hitTime") LocalDateTime hitTime);

    /**
     * Bugunun henuz Telegram'a gonderilmemis sonuclarini getirir.
     *
     * @param screenerDay tarama gunu
     * @return gonderilmemis tarama sonuclari (stockName ve screenerTime'a gore sirali)
     */
    @Query("SELECT sr FROM ScreenerResultModel sr " +
           "WHERE sr.screenerDay = :screenerDay " +
           "AND (sr.telegramSent = false OR sr.telegramSent IS NULL) " +
           "ORDER BY sr.stockName, sr.screenerTime")
    List<ScreenerResultModel> findTodayUnsent(@Param("screenerDay") LocalDate screenerDay);

    /**
     * Belirtilen kayitlari Telegram gonderildi olarak toplu isaretler.
     *
     * <p><b>Onemli:</b> {@code screenerCount} ve {@code commonNames} parametreleri
     * hisse bazli (grup bazli) degerlerdir. Bu method ayni hisseye ait ID'ler ile
     * cagirilmalidir; farkli hisselere ait ID'ler karistirilmamalidir.</p>
     *
     * @param ids isaretlenecek kayit id'leri (ayni stockName'e ait olmali)
     * @param sentTime gonderim zamani
     * @param screenerCount bu hissenin ciktigi farkli tarama sayisi
     * @param commonNames bu hissenin ciktigi tarama adlari (CSV)
     * @return guncellenen kayit sayisi
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ScreenerResultModel sr SET " +
           "sr.telegramSent = true, " +
           "sr.telegramSentTime = :sentTime, " +
           "sr.screenerCount = :screenerCount, " +
           "sr.commonScreenerNames = :commonNames " +
           "WHERE sr.id IN (:ids)")
    int bulkMarkTelegramSent(@Param("ids") List<Long> ids,
                              @Param("sentTime") LocalDateTime sentTime,
                              @Param("screenerCount") Integer screenerCount,
                              @Param("commonNames") String commonNames);
}
