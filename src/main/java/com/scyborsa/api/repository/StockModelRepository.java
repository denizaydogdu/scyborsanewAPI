package com.scyborsa.api.repository;

import com.scyborsa.api.model.StockModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Hisse senedi master verisi için JPA repository'si.
 * <p>
 * {@link StockModel} entity'si üzerinde CRUD işlemleri, aktif hisse sorguları
 * ve yasaklı/yasaksız filtreleme yeteneği sağlar.
 * </p>
 *
 * @see StockModel
 */
@Repository
public interface StockModelRepository extends JpaRepository<StockModel, Long> {

    /**
     * Borsa koduna göre hisse arar.
     *
     * @param stockName borsa kodu (ör. "THYAO")
     * @return bulunan hisse veya boş Optional
     */
    Optional<StockModel> findByStockName(String stockName);

    /**
     * Yasaklı olmayan (aktif) tüm hisseleri borsa koduna göre sıralı getirir.
     *
     * @return aktif hisse listesi (alfabetik sıralı)
     */
    @Query("SELECT s FROM StockModel s WHERE s.isBanned = false ORDER BY s.stockName")
    List<StockModel> findActiveStocks();

    /**
     * Tüm hisseleri (aktif + yasaklı) borsa koduna göre sıralı getirir.
     *
     * @return tüm hisseler (alfabetik sıralı)
     */
    @Query("SELECT s FROM StockModel s ORDER BY s.stockName")
    List<StockModel> findAllOrdered();

    /**
     * Belirtilen borsa kodunda bir hisse var mı kontrol eder.
     *
     * @param stockName kontrol edilecek borsa kodu
     * @return varsa {@code true}, yoksa {@code false}
     */
    boolean existsByStockName(String stockName);

    /**
     * Aktif (yasaklı olmayan) hisse sayısını döndürür.
     *
     * @return aktif hisse adedi
     */
    @Query("SELECT COUNT(s) FROM StockModel s WHERE s.isBanned = false")
    long countActiveStocks();

    /**
     * Yasaklı hisselerin borsa kodlarını döndürür.
     *
     * @return yasaklı hisse kodları listesi
     */
    @Query("SELECT s.stockName FROM StockModel s WHERE s.isBanned = true")
    List<String> findBannedStockNames();
}
