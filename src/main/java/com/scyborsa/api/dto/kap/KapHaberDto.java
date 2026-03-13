package com.scyborsa.api.dto.kap;

import lombok.Builder;
import lombok.Data;

/**
 * KAP (Kamuyu Aydınlatma Platformu) haber bildirimi DTO'su.
 * <p>
 * KAP'tan alınan şirket haberlerinin client'a iletilmesi için kullanılır.
 * WebSocket {@code /topic/kap-news} kanalı ve REST endpoint'leri üzerinden döner.
 * </p>
 *
 * @see com.scyborsa.api.model.haber.KapHaber
 */
@Data
@Builder
public class KapHaberDto {

    /** Veritabanındaki benzersiz kayıt kimliği. */
    private Long id;

    /** Haberin ait olduğu şirketin borsa kodu (ör. "THYAO"). */
    private String companyCode;

    /** Haberin başlığı. */
    private String title;

    /** Haberin konusu/kategorisi (ör. "Özel Durum Açıklaması"). */
    private String subject;

    /** Haberin özet metni. */
    private String summary;

    /** Haberin KAP'ta yayınlanma tarihi (String formatında). */
    private String publishedAt;
}
