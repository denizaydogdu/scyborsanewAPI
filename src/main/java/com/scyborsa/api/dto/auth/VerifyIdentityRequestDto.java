package com.scyborsa.api.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Kimlik dogrulama istegi DTO sinifi.
 *
 * <p>Sifre sifirlama oncesinde kullanicinin email ve telefon numarasi
 * ile kimligini dogrulamak icin kullanilir.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VerifyIdentityRequestDto {

    /** Kullanicinin e-posta adresi. */
    private String email;

    /** Kullanicinin telefon numarasi. */
    private String phoneNumber;
}
