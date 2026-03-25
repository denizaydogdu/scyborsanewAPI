package com.scyborsa.api.repository;

import com.scyborsa.api.model.LoginHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Giris gecmisi repository'si.
 *
 * <p>Kullanici giris denemelerinin veritabani erisim katmanini saglar.</p>
 *
 * @see LoginHistory
 */
@Repository
public interface LoginHistoryRepository extends JpaRepository<LoginHistory, Long> {

    /**
     * Kullanicinin giris gecmisini sayfalanmis olarak getirir (backoffice icin).
     *
     * @param userId kullanici ID'si
     * @param pageable sayfalama bilgisi
     * @return giris gecmisi listesi
     */
    List<LoginHistory> findByAppUserIdOrderByLoginDateDesc(Long userId, Pageable pageable);
}
