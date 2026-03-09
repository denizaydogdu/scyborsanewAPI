package com.scyborsa.api.repository;

import com.scyborsa.api.model.AraciKurum;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Araci kurum repository arayuzu.
 *
 * <p>{@link AraciKurum} entity'si icin veritabani erisim katmani.
 * Spring Data JPA tarafindan otomatik implement edilir.</p>
 *
 * @see AraciKurum
 */
@Repository
public interface AraciKurumRepository extends JpaRepository<AraciKurum, Long> {

    /**
     * Aktif araci kurumlari sira numarasina gore sirali getirir.
     *
     * @return aktif araci kurumlarin sirali listesi
     */
    List<AraciKurum> findByAktifTrueOrderBySiraNoAsc();

    /**
     * Araci kurum koduna gore arar.
     *
     * @param code aranacak araci kurum kodu
     * @return bulunan araci kurum; yoksa {@link Optional#empty()}
     */
    Optional<AraciKurum> findByCode(String code);

    /**
     * Belirtilen kodda araci kurum var mi kontrol eder.
     *
     * @param code kontrol edilecek araci kurum kodu
     * @return varsa {@code true}, yoksa {@code false}
     */
    boolean existsByCode(String code);

    /**
     * Tum araci kurumlari basliga gore sirali getirir.
     *
     * @return tum araci kurumlarin basliga gore sirali listesi
     */
    List<AraciKurum> findAllByOrderByTitleAsc();

    /**
     * Aktif araci kurum sayisini dondurur.
     *
     * @return aktif araci kurum sayisi
     */
    long countByAktifTrue();

    /**
     * Belirtilen kod koleksiyonundaki araci kurumlari getirir.
     *
     * @param codes aranacak araci kurum kodlari
     * @return eslesen araci kurumlarin listesi
     */
    List<AraciKurum> findByCodeIn(Collection<String> codes);
}
