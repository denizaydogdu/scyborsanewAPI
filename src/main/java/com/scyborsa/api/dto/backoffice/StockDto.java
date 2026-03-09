package com.scyborsa.api.dto.backoffice;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Backoffice hisse bilgileri DTO sinifi.
 *
 * <p>StockModel entity'sinin basitlestirilmis versiyonu.
 * REST API uzerinden hisse listesi transfer edilirken kullanilir.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockDto {

    /** Hisse ID'si. */
    private Long id;

    /** Hisse borsa kodu (orn: "THYAO"). */
    private String stockName;

    /** Hisse tipi kimligi. */
    private Long stockTypeId;

    /** Hissenin yasakli olup olmadigi. */
    private Boolean isBanned;

    /** Yasaklama nedeni aciklamasi. */
    private String bannedSituation;

    /** Kayit olusturma zamani. */
    private String createTime;
}
