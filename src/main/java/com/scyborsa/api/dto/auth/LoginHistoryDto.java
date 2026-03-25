package com.scyborsa.api.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Giris gecmisi DTO sinifi.
 *
 * <p>Backoffice'te kullanicinin giris gecmisini gostermek icin kullanilir.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginHistoryDto {

    /** Kayit ID'si. */
    private Long id;

    /** Giris denemesi zamani. */
    private LocalDateTime loginDate;

    /** IP adresi. */
    private String ipAddress;

    /** Tarayici/istemci bilgisi. */
    private String userAgent;

    /** Giris basarili mi? */
    private boolean success;

    /** Basarisizlik nedeni. */
    private String failureReason;
}
