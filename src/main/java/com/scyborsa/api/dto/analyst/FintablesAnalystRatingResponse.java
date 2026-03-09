package com.scyborsa.api.dto.analyst;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Fintables analist tavsiye API response wrapper DTO'su.
 *
 * <p>Fintables API paginated response formatini tasir:
 * {@code {count, next, previous, results[]}}.</p>
 *
 * @see AnalystRatingDto
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FintablesAnalystRatingResponse {

    /** Toplam sonuc sayisi. */
    private Integer count;

    /** Sonraki sayfa URL'i (null ise son sayfa). */
    private String next;

    /** Onceki sayfa URL'i (null ise ilk sayfa). */
    private String previous;

    /** Tavsiye sonuclari. */
    private List<AnalystRatingDto> results;
}
