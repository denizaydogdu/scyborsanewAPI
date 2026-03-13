package com.scyborsa.api.model.haber;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * KAP (Kamuyu Aydinlatma Platformu) haber bildirimi entity'si.
 * <p>
 * KAP'tan alinip WebSocket ile client'lara iletilen haberlerin veritabani kaydi.
 * Mukerrer gonderimi onlemek icin UUID bazli tekil kontrol yapilir.
 * Tablo adi: {@code kap_sent_news}.
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

    /** KAP haberinin benzersiz tanimlayicisi. Mukerrer gonderimi onlemek icin kullanilir. */
    @Column(unique = true, nullable = false, length = 100)
    private String uuid;

    /** Haberin ait oldugu sirketin borsa kodu (orn. "THYAO"). */
    @Column(name = "company_code")
    private String companyCode;

    /** Haberin WebSocket ile client'lara gonderildigi zaman. */
    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    /** Haberin KAP'ta yayinlanma tarihi (String formatinda, KAP'tan gelen ham deger). */
    @Column(name = "published_at")
    private String publishedAt;

    /** Haberin basligi. */
    private String title;

    /** Haberin konusu/kategorisi (orn. "Ozel Durum Aciklamasi"). */
    private String subject;

    /** Haberin ozet metni. Uzun metin olabileceginden TEXT tipinde saklanir. */
    @Column(columnDefinition = "TEXT")
    private String summary;

    /** Habere eklenen ek not/aciklama. Opsiyonel, TEXT tipinde saklanir. */
    @Column(columnDefinition = "TEXT")
    private String note;

    /** KAP sistemindeki orijinal haber kimligi. Kaynak eslestirme icin kullanilir. */
    @Column(name = "kap_id")
    private Long kapId;
}
