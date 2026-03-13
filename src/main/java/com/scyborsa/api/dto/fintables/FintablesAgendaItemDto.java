package com.scyborsa.api.dto.fintables;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Fintables haftalik ajanda API response item DTO'su.
 *
 * <p>{@code /mobile/agenda/?time=thisWeek} veya {@code nextWeek} endpoint'inden donen
 * her bir ajanda ogesini temsil eder. Bilanco, temettu, makro takvim ve webinar
 * gibi farkli turlerde ogeleri icerir.</p>
 *
 * @see FintablesTopicFeedItemDto
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FintablesAgendaItemDto {

    /** Ajanda ogesi basligi. */
    private String title;

    /** Oge turu: next_sheet, dividend, macro, webinar. */
    private String type;

    /** Ajanda gunu (yyyy-MM-dd formati, orn: "2026-03-09"). */
    private String day;

    /** Ajanda saati (HH:mm formati, orn: "11:00") — null olabilir. */
    private String time;

    /** Oge gorsel URL'i. */
    @JsonProperty("image_url")
    private String imageUrl;

    /** Oge gorseli (alternatif alan). */
    private String image;

    /** Gorsel yokken gosterilecek fallback metni. */
    @JsonProperty("image_fallback_text")
    private String imageFallbackText;

    /** Ajanda ogesi detay verileri (label-value ciftleri). */
    private List<AgendaData> data;

    /** Ajanda ogesi linki. */
    private String link;

    /** Gosterim durumu. */
    private String visibility;

    /**
     * Ajanda ogesi detay verisi (label-value cifti).
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AgendaData {

        /** Veri etiketi (orn: "Bilanço Dönemi", "Temettü Verimi"). */
        private String label;

        /** Veri degeri. */
        private String value;
    }
}
