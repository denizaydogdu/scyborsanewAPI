package com.scyborsa.api.repository;

import com.scyborsa.api.enums.YatirimVadesi;
import com.scyborsa.api.model.TakipHissesi;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Takip hissesi veritabani erisim katmani.
 *
 * <p>Aktif/pasif filtreleme, vade bazli sorgulama ve
 * duplikat kontrol metotlari sunar.</p>
 *
 * @see TakipHissesi
 * @see com.scyborsa.api.service.TakipHissesiService
 */
@Repository
public interface TakipHissesiRepository extends JpaRepository<TakipHissesi, Long> {

    /**
     * Aktif takip hisselerini sira numarasina gore sirali getirir.
     *
     * @return aktif takip hissesi listesi, siraNo ASC
     */
    List<TakipHissesi> findByAktifTrueOrderBySiraNoAsc();

    /**
     * Belirtilen vadedeki aktif takip hisselerini sira numarasina gore sirali getirir.
     *
     * @param vade yatirim vadesi filtresi
     * @return filtrelenmis aktif takip hissesi listesi, siraNo ASC
     */
    List<TakipHissesi> findByAktifTrueAndVadeOrderBySiraNoAsc(YatirimVadesi vade);

    /**
     * Ayni hisse kodu ve vadede aktif bir kayit olup olmadigini kontrol eder.
     *
     * <p>Application-level duplikat kontrolu icin kullanilir.</p>
     *
     * @param hisseKodu hisse borsa kodu
     * @param vade      yatirim vadesi
     * @return true ise duplikat mevcut
     */
    boolean existsByHisseKoduAndVadeAndAktifTrue(String hisseKodu, YatirimVadesi vade);
}
