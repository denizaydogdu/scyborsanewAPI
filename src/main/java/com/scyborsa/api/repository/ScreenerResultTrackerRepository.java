package com.scyborsa.api.repository;

import com.scyborsa.api.model.ScreenerResultTrackerModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Tarama sonuç takibi (tracker) için JPA repository'si.
 *
 * <p>Tarama sonuçlarında çıkan hisselerin sonraki günlerdeki OHLC performansını
 * izlemek üzere {@link ScreenerResultTrackerModel} üzerinde sorgu sağlar.</p>
 *
 * @see ScreenerResultTrackerModel
 */
@Repository
public interface ScreenerResultTrackerRepository extends JpaRepository<ScreenerResultTrackerModel, Long> {

    /**
     * Belirli bir tarama gününe ait tüm tracker kayıtlarını getirir.
     *
     * @param screenerDay tarama günü
     * @return tracker kayıtları
     */
    List<ScreenerResultTrackerModel> findByScreenerDay(LocalDate screenerDay);

    /**
     * Belirli bir hissenin belirli bir tarama gününe ait tracker kayıtlarını getirir.
     *
     * @param stockName hisse kodu
     * @param screenerDay tarama günü
     * @return tracker kayıtları
     */
    List<ScreenerResultTrackerModel> findByStockNameAndScreenerDay(String stockName, LocalDate screenerDay);

    /**
     * Duplicate detection: aynı hisse + tarama günü + takip günü kombinasyonu var mı?
     *
     * @param stockName hisse kodu
     * @param screenerDay tarama günü
     * @param scanDay takip günü
     * @return {@code true} ise zaten kayıtlı
     */
    boolean existsByStockNameAndScreenerDayAndScanDay(String stockName, LocalDate screenerDay, LocalDate scanDay);
}
