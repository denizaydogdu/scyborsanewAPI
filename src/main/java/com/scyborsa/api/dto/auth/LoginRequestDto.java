package com.scyborsa.api.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Giris istegi DTO sinifi.
 *
 * <p>Kullanici giris isleminde e-posta ve password bilgisini tasir.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequestDto {

    /** E-posta adresi. */
    private String email;

    /** Sifre (plain text). */
    @ToString.Exclude
    private String password;
}
