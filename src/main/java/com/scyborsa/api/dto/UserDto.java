package com.scyborsa.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Kullanici DTO sinifi.
 *
 * <p>Kullanici bilgilerini disariya aktarmak icin kullanilir.
 * Sifre bilgisi sadece create/update isteklerinde gonderilir,
 * response'ta {@code null} olarak doner.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDto {

    /** Kullanici ID'si. */
    private Long id;

    /** Kullanici adi. */
    private String username;

    /** Kullanicinin e-posta adresi. */
    private String email;

    /** Kullanicinin ad soyad bilgisi. */
    private String adSoyad;

    /** Kullanici rolu (ADMIN veya USER). */
    private String role;

    /** Erisim baslangic tarihi. */
    private LocalDate validFrom;

    /** Erisim bitis tarihi. */
    private LocalDate validTo;

    /** Kullanici grubu. */
    private String userGroup;

    /** Telegram kullanici adi. */
    private String telegramUsername;

    /** Telefon numarasi. */
    private String phoneNumber;

    /** Aktif/pasif durumu. */
    private Boolean aktif;

    /** Kayit olusturma zamani. */
    private LocalDateTime createTime;

    /** Sifre (sadece create/update isteklerinde kullanilir, response'ta null). */
    @ToString.Exclude
    private String password;

    /** Son basarili giris tarihi. */
    private LocalDateTime lastLoginDate;

    /** Son basarili giris IP adresi. */
    private String lastLoginIp;

    /** Toplam basarili giris sayisi. */
    private Integer loginCount;
}
