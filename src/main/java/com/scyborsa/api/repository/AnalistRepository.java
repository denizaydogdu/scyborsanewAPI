package com.scyborsa.api.repository;

import com.scyborsa.api.model.Analist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Analist repository arayuzu.
 *
 * <p>{@link Analist} entity'si icin veritabani erisim katmani.
 * Spring Data JPA tarafindan otomatik implement edilir.</p>
 *
 * @see Analist
 */
@Repository
public interface AnalistRepository extends JpaRepository<Analist, Long> {

    /**
     * Aktif analistleri sira numarasina gore sirali getirir.
     *
     * @return aktif analistlerin sirali listesi
     */
    List<Analist> findByAktifTrueOrderBySiraNoAsc();

    /**
     * Analist adina gore analist arar.
     *
     * @param ad aranacak analist adi
     * @return bulunan analist; yoksa {@link Optional#empty()}
     */
    Optional<Analist> findByAd(String ad);

    /**
     * Belirtilen isimde analist var mi kontrol eder.
     *
     * @param ad kontrol edilecek analist adi
     * @return varsa {@code true}, yoksa {@code false}
     */
    boolean existsByAd(String ad);
}
