package com.scyborsa.api.dto.analyst;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Fintables analist tavsiye verisi DTO'su.
 *
 * <p>Fintables API'den gelen analist tavsiye bilgilerini tasir.
 * Snake_case JSON alanlari {@code @JsonAlias} ile camelCase'e eslestirilir.</p>
 *
 * <p>Fintables API gercek alan adlari: {@code published_at}, {@code code},
 * {@code price_target}, {@code type}, {@code in_model_portfolio}.</p>
 *
 * @see BrokerageDto
 * @see AttachmentDto
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnalystRatingDto {

    /** Tavsiye tipi (al, tut, sat, endekse paralel, vb.). */
    @JsonAlias("type")
    private String ratingType;

    /** Hedef fiyat (TL). */
    @JsonAlias("price_target")
    private Double targetPrice;

    /** Tavsiye tarihi (ISO-8601, ornek: 2026-03-06T14:34:49Z). */
    @JsonAlias("published_at")
    private String date;

    /** Araci kurum bilgisi. */
    private BrokerageDto brokerage;

    /** Hisse kodu (BIST ticker, ornek: PETKM). */
    @JsonAlias("code")
    private String stockCode;

    /** Ek dosyalar (PDF raporlar). */
    private List<AttachmentDto> attachments;

    /** Model portfoy tavsiyesi mi. */
    @JsonAlias("in_model_portfolio")
    private boolean modelPortfolio;

    /** Katilim endeksi uyesi mi? */
    @JsonProperty("katilim")
    private boolean katilim;

    /**
     * Araci kurum bilgisi DTO'su.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BrokerageDto {
        /** Araci kurum kodu (orn. "MLB", "YKR"). */
        private String code;
        /** Araci kurum tam adi. */
        private String title;
        /** Araci kurum logo URL'i. */
        private String logo;

        /** Araci kurum kisa adi. */
        @JsonAlias("short_title")
        private String shortTitle;
    }

    /**
     * Tavsiye ek dosya DTO'su.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AttachmentDto {
        /** Ek dosya ID'si. */
        private Integer id;
        /** PDF dosya URL'i. */
        private String file;
    }
}
