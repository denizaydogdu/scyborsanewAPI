package com.scyborsa.api.repository;

import com.scyborsa.api.enums.EnrichmentDataTypeEnum;
import com.scyborsa.api.model.EnrichmentCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

/**
 * Zenginleştirme cache repository'si.
 *
 * <p>{@code enrichment_cache} tablosuna CRUD erişimi sağlar.</p>
 */
public interface EnrichmentCacheRepository extends JpaRepository<EnrichmentCache, Long> {

    /**
     * Belirtilen hisse, tarih ve veri tipi için cache kaydını getirir.
     *
     * @param stockCode hisse kodu
     * @param cacheDate cache tarihi
     * @param dataType  veri tipi (AKD/TAKAS)
     * @return cache kaydı (varsa)
     */
    Optional<EnrichmentCache> findByStockCodeAndCacheDateAndDataType(
            String stockCode, LocalDate cacheDate, EnrichmentDataTypeEnum dataType);

    /**
     * Belirtilen hisse, tarih ve veri tipi için cache kaydının varlığını kontrol eder.
     *
     * @param stockCode hisse kodu
     * @param cacheDate cache tarihi
     * @param dataType  veri tipi
     * @return kayıt varsa {@code true}
     */
    boolean existsByStockCodeAndCacheDateAndDataType(
            String stockCode, LocalDate cacheDate, EnrichmentDataTypeEnum dataType);

    /**
     * Belirtilen tarih ve veri tipi için cache'lenmiş hisse kodlarını getirir.
     *
     * <p>Sync job başında hangi hisselerin zaten cache'lendiğini belirlemek için
     * kullanılır (batch skip — idempotent).</p>
     *
     * @param date     cache tarihi
     * @param dataType veri tipi
     * @return cache'lenmiş hisse kodları seti
     */
    @Query("SELECT DISTINCT e.stockCode FROM EnrichmentCache e " +
            "WHERE e.cacheDate = :date AND e.dataType = :dataType")
    Set<String> findCachedStockCodes(
            @Param("date") LocalDate date,
            @Param("dataType") EnrichmentDataTypeEnum dataType);

    /**
     * Belirtilen tarihten eski cache kayıtlarını siler.
     *
     * <p>Sync job'da eski verilerin temizlenmesi için kullanılır (30 gün).</p>
     *
     * @param cutoffDate bu tarihten önceki kayıtlar silinir
     * @return silinen kayıt sayısı
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM EnrichmentCache e WHERE e.cacheDate < :cutoffDate")
    int deleteOlderThan(@Param("cutoffDate") LocalDate cutoffDate);
}
