package com.scyborsa.api.repository;

import com.scyborsa.api.model.KapHaber;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/**
 * KAP haber bildirimleri için JPA repository'si.
 * <p>
 * {@link KapHaber} entity'si üzerinde CRUD işlemleri ve
 * son gönderilen haberleri sorgulama yeteneği sağlar.
 * </p>
 *
 * @see KapHaber
 */
public interface KapHaberRepository extends JpaRepository<KapHaber, Long> {

    /**
     * En son gönderilen 10 KAP haberini getirir.
     * Gönderim zamanına ({@code sentAt}) göre azalan sırada sıralanır.
     *
     * @return en son 10 haber kaydı
     */
    List<KapHaber> findTop10ByOrderBySentAtDesc();
}
