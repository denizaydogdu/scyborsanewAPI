package com.scyborsa.api.repository;

import com.scyborsa.api.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

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
     * Tum kullanicilari ID sirasina gore getirir.
     *
     * @return kullanici listesi
     */
    List<AppUser> findAllByOrderByIdAsc();
}
