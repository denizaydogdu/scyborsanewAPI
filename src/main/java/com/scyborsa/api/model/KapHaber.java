package com.scyborsa.api.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * KAP (Kamuyu Aydınlatma Platformu) haber bildirimi entity'si.
 * <p>
 * KAP'tan alınıp WebSocket ile client'lara iletilen haberlerin veritabanı kaydı.
 * Mükerrer gönderimi önlemek için UUID bazlı tekil kontrol yapılır.
 * Tablo adı: {@code kap_sent_news}.
 * </p>
 *
 * @see com.scyborsa.api.dto.kap.KapHaberDto
 * @see com.scyborsa.api.repository.KapHaberRepository
 */
@Data
@Entity
@Table(name = "kap_sent_news")
public class KapHaber {

    /** Otomatik artan birincil anahtar. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** KAP haberinin benzersiz tanımlayıcısı. Mükerrer gönderimi önlemek için kullanılır. */
    @Column(unique = true, nullable = false, length = 100)
    private String uuid;

    /** Haberin ait olduğu şirketin borsa kodu (ör. "THYAO"). */
    @Column(name = "company_code")
    private String companyCode;

    /** Haberin WebSocket ile client'lara gönderildiği zaman. */
    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    /** Haberin KAP'ta yayınlanma tarihi (String formatında, KAP'tan gelen ham değer). */
    @Column(name = "published_at")
    private String publishedAt;

    /** Haberin başlığı. */
    private String title;

    /** Haberin konusu/kategorisi (ör. "Özel Durum Açıklaması"). */
    private String subject;

    /** Haberin özet metni. Uzun metin olabileceğinden TEXT tipinde saklanır. */
    @Column(columnDefinition = "TEXT")
    private String summary;

    /** Habere eklenen ek not/açıklama. Opsiyonel, TEXT tipinde saklanır. */
    @Column(columnDefinition = "TEXT")
    private String note;

    /** KAP sistemindeki orijinal haber kimliği. Kaynak eşleştirme için kullanılır. */
    @Column(name = "kap_id")
    private Long kapId;
}
