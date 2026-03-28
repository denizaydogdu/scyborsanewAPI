package com.scyborsa.api.dto.watchlist;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Takip listesi veri transfer nesnesi.
 *
 * <p>Kullanicinin olusturdugu takip listelerinin bilgilerini tasir.
 * {@code stockCount} alani sorgu zamaninda hesaplanir.</p>
 *
 * @see com.scyborsa.api.model.Watchlist
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WatchlistDto {

    /** Takip listesi ID'si. */
    private Long id;

    /** Liste adi (maks 50 karakter). */
    private String name;

    /** Liste aciklamasi (opsiyonel, maks 200 karakter). */
    private String description;

    /** Gorunum sirasi. */
    private Integer displayOrder;

    /** Varsayilan liste mi. */
    private Boolean isDefault;

    /** Listedeki hisse sayisi (hesaplanan alan). */
    private Integer stockCount;

    /** Olusturulma zamani. */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createTime;

    /** Son guncelleme zamani. */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updateTime;
}
