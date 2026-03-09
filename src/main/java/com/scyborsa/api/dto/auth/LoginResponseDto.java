package com.scyborsa.api.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Giris yaniti DTO sinifi.
 *
 * <p>Giris isleminin sonucunu istemciye iletir. Basarili ise
 * rol ve kullanici bilgisi, basarisiz ise hata mesaji icerir.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginResponseDto {

    /** Giris basarili mi? */
    private boolean success;

    /** Kullanici rolu (basarili ise). ADMIN veya USER. */
    private String role;

    /** Hata mesaji (basarisiz ise). */
    private String message;

    /** Kullanici adi. */
    private String username;

    /** Kullanicinin e-posta adresi. */
    private String email;

    /** Kullanicinin ad soyad bilgisi. */
    private String adSoyad;
}
