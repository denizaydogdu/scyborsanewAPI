package com.scyborsa.api.dto.fintables;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Fintables topic-feed tek item DTO'su.
 *
 * <p>Topic feed'deki her bir ogeyi temsil eder. Uc farkli tur icerebilir:</p>
 * <ul>
 *   <li>{@code post} — Analist notlari, sektor haberleri</li>
 *   <li>{@code news} — KAP haberleri (kap_id ile zenginlestirilmis)</li>
 *   <li>{@code newsletter} — Gunluk bulten</li>
 * </ul>
 *
 * <p>Her tur icin ilgili nested data objesi dolu gelir, diger turler null olur.</p>
 *
 * @see FintablesTopicFeedResponseDto
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FintablesTopicFeedItemDto {

    /** Benzersiz item kimligi (UUID string). */
    private String id;

    /** Item turu: "post", "news", "newsletter". */
    private String type;

    /** Item basligi. */
    private String title;

    /** Yayinlanma zamani (ISO-8601 formati, API'de "date" alani). */
    private String date;

    /** Yayinlanma zamani (alternatif alan adi). */
    @JsonProperty("published_at")
    private String publishedAt;

    /** Post verisi (type="post" ise dolu). */
    private PostData post;

    /** Haber verisi (type="news" ise dolu). */
    private NewsData news;

    /** Bulten verisi (type="newsletter" ise dolu). */
    private NewsletterData newsletter;

    /** Iliskili topic referanslari (sembol bilgisi icin). */
    private List<TopicRef> topics;

    /** Yazar bilgisi. */
    private AuthorData author;

    /** Kategori bilgisi. */
    private CategoryData category;

    /**
     * Post (analist notu / sektor haberi) verisi.
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PostData {

        /** Post ID. */
        private String id;

        /** Post basligi. */
        private String title;

        /** Post ozeti. */
        private String summary;

        /** Post gorsel URL'i. */
        @JsonProperty("image_url")
        private String imageUrl;

        /** Gorsel fallback metni. */
        @JsonProperty("image_fallback_text")
        private String imageFallbackText;

        /** Ek dosyalar. */
        private List<AttachmentData> attachments;
    }

    /**
     * Post ek dosya verisi.
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AttachmentData {

        /** Ek dosya ID. */
        private String id;

        /** Dosya URL'i. */
        private String url;

        /** Dosya adi. */
        private String name;
    }

    /**
     * KAP haber verisi (zenginlestirilmis).
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NewsData {

        /** Haber ID. */
        private String id;

        /** KAP bildirim ID'si. */
        @JsonProperty("kap_id")
        private String kapId;

        /** Haber basligi. */
        private String title;

        /** Insan yazimi not basligi (zenginlestirme). */
        @JsonProperty("note_title")
        private String noteTitle;

        /** Insan yazimi not icerigi (zenginlestirme). */
        private String note;

        /** Haber gorsel URL'i. */
        @JsonProperty("image_url")
        private String imageUrl;

        /** Gorsel fallback metni. */
        @JsonProperty("image_fallback_text")
        private String imageFallbackText;

        /** Habere iliskili semboller. */
        private List<NewsSymbol> symbols;
    }

    /**
     * KAP haber iliskili sembol.
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NewsSymbol {

        /** Sembol ID. */
        private String id;

        /** Sembol kodu (orn: "THYAO"). */
        private String code;

        /** Sembol adi (orn: "Türk Hava Yolları"). */
        private String name;
    }

    /**
     * Newsletter (gunluk bulten) verisi.
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NewsletterData {

        /** Bulten ID. */
        private String id;

        /** Bulten basligi. */
        private String title;

        /** Bulten ozeti. */
        private String summary;

        /** Bulten gorsel URL'i. */
        @JsonProperty("image_url")
        private String imageUrl;

        /** Gorsel fallback metni. */
        @JsonProperty("image_fallback_text")
        private String imageFallbackText;
    }

    /**
     * Yazar bilgisi.
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuthorData {

        /** Yazar ID. */
        private String id;

        /** Yazar adi. */
        private String name;

        /** Yazar profil gorseli. */
        @JsonProperty("profile_image")
        private String profileImage;
    }

    /**
     * Kategori bilgisi.
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CategoryData {

        /** Kategori ID. */
        private String id;

        /** Kategori adi. */
        private String name;

        /** Kategori slug'i. */
        private String slug;
    }

    /**
     * Topic referansi (sembol veya sektor).
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TopicRef {

        /** Topic ID (sembol kodu olarak da kullanilir). */
        private String id;

        /** Topic adi. */
        private String name;

        /** Topic turu: "symbol", "sector", "index" vb. */
        private String type;
    }
}
