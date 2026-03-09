package com.scyborsa.api.repository;

import com.scyborsa.api.model.ModelPortfoyKurum;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Model portföy aracı kurum repository arayüzü.
 *
 * <p>{@link ModelPortfoyKurum} entity'si için veritabanı erişim katmanı.
 * Spring Data JPA tarafından otomatik implement edilir.</p>
 *
 * @see ModelPortfoyKurum
 */
@Repository
public interface ModelPortfoyKurumRepository extends JpaRepository<ModelPortfoyKurum, Long> {

    /**
     * Aktif kurumları sıra numarasına göre sıralı getirir.
     *
     * @return aktif kurumların sıralı listesi
     */
    List<ModelPortfoyKurum> findByAktifTrueOrderBySiraNoAsc();

    /**
     * Kurum adına göre kurum arar.
     *
     * @param kurumAdi aranacak kurum adı
     * @return bulunan kurum; yoksa {@link Optional#empty()}
     */
    Optional<ModelPortfoyKurum> findByKurumAdi(String kurumAdi);

    /**
     * Belirtilen isimde kurum var mı kontrol eder.
     *
     * @param kurumAdi kontrol edilecek kurum adı
     * @return varsa {@code true}, yoksa {@code false}
     */
    boolean existsByKurumAdi(String kurumAdi);
}
