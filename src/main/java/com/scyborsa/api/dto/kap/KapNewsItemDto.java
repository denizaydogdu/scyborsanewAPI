package com.scyborsa.api.dto.kap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * Tek bir KAP haber öğesini temsil eden DTO.
 *
 * <p>TradingView news-mediator API'sinden dönen her bir haber kaydının alanlarını taşır.
 * {@code formattedPublished} alanı servis tarafından Türkiye saatiyle formatlanarak set edilir.</p>
 *
 * @see KapRelatedSymbolDto
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class KapNewsItemDto {

    /** Haberin benzersiz kimliği. */
    private String id;

    /** Haber başlığı. */
    private String title;

    /** Haber sağlayıcı adı (örn: "kap"). */
    private String provider;

    /** Kaynak logo kimliği. */
    private String sourceLogoId;

    /** Haber kaynağı adı. */
    private String source;

    /** Haber detay yolu. */
    private String storyPath;

    /** Yayınlanma zamanı (unix epoch saniye). Boxed type — JSON'da yoksa null kalır, epoch 0 üretmez. */
    private Long published;

    /** Haber aciliyet seviyesi. Boxed type — JSON'da yoksa null kalır, exception üretmez. */
    private Integer urgency;

    /** İlişkili semboller listesi. */
    private List<KapRelatedSymbolDto> relatedSymbols;

    /** Formatlanmış yayınlanma zamanı (örn: "03 Mart 2026 14:30"). Servis tarafından set edilir. */
    private String formattedPublished;
}
