package com.scyborsa.api.dto.kap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * KAP haberine ilişkili sembol bilgisi DTO'su.
 *
 * <p>{@code logoUrl} alanı servis tarafından {@code logoBaseUrl + logoid + ".svg"} şeklinde
 * enrichment yapılarak set edilir.</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class KapRelatedSymbolDto {

    /** Sembol kodu (örn: "BIST:THYAO"). */
    private String symbol;

    /** Logo kimliği (CDN'deki dosya adı). */
    private String logoid;

    /** Tam logo URL'i. Servis tarafından enrichment ile set edilir. */
    private String logoUrl;
}
