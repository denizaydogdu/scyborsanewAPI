package com.scyborsa.api.repository;

import com.scyborsa.api.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Kullanici repository interface'i.
 *
 * <p>{@link AppUser} entity'si icin veritabani erisim katmani.</p>
 */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /**
     * Kullanici adina gore kullanici bulur.
     *
     * @param username kullanici adi
     * @return kullanici (Optional)
     */
    Optional<AppUser> findByUsername(String username);

    /**
     * Kullanici adinin mevcut olup olmadigini kontrol eder.
     *
     * @param username kullanici adi
     * @return mevcutsa true
     */
    boolean existsByUsername(String username);

    /**
     * E-posta adresine gore kullanici bulur.
     *
     * @param email e-posta adresi
     * @return kullanici (Optional)
     */
    Optional<AppUser> findByEmail(String email);

    /**
     * E-posta adresinin mevcut olup olmadigini kontrol eder.
     *
     * @param email e-posta adresi
     * @return mevcutsa true
     */
    boolean existsByEmail(String email);

    /**
     * Telegram kullanici adinin mevcut olup olmadigini kontrol eder.
     *
     * @param telegramUsername telegram kullanici adi
     * @return mevcutsa true
     */
    boolean existsByTelegramUsernameIgnoreCase(String telegramUsername);

    /**
     * Tum kullanicilari ID sirasina gore getirir.
     *
     * @return kullanici listesi
     */
    List<AppUser> findAllByOrderByIdAsc();

    /**
     * Giris ozet bilgilerini atomik olarak gunceller (race condition onlemi).
     *
     * @param userId    kullanici ID'si
     * @param loginDate giris zamani
     * @param ip        istemci IP adresi
     * @return guncellenen satir sayisi
     */
    @Transactional
    @Modifying
    @Query("UPDATE AppUser u SET u.loginCount = COALESCE(u.loginCount, 0) + 1, u.lastLoginDate = :loginDate, u.lastLoginIp = :ip WHERE u.id = :userId")
    int updateLoginSummary(@Param("userId") Long userId, @Param("loginDate") LocalDateTime loginDate, @Param("ip") String ip);
}
