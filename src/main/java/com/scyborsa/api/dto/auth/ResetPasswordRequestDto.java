package com.scyborsa.api.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Sifre sifirlama istegi DTO sinifi.
 *
 * <p>Email ve telefon numarasi ile kimlik dogrulandiktan sonra
 * yeni sifre belirlemek icin kullanilir.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResetPasswordRequestDto {

    /** Kullanicinin e-posta adresi. */
    private String email;

    /** Kullanicinin telefon numarasi. */
    private String phoneNumber;

    /** Yeni sifre (plain text, minimum 6 karakter). */
    @ToString.Exclude
    private String newPassword;
}
