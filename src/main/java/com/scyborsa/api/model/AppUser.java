package com.scyborsa.api.model;

import com.scyborsa.api.enums.UserRoleEnum;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Kullanici entity sinifi.
 *
 * <p>Sistem kullanicilarini temsil eder. Her kullanici icin kullanici adi,
 * BCrypt ile hashlanmis sifre, rol, gecerlilik tarihleri ve aktiflik durumu tutulur.</p>
 *
 * <p><b>Tablo:</b> {@code app_user} (PostgreSQL'de "user" reserved keyword oldugu
 * icin "app_user" kullanilir)</p>
 *
 * @see com.scyborsa.api.repository.AppUserRepository
 * @see com.scyborsa.api.service.UserService
 */
@Getter
@Setter
@ToString(exclude = "password")
@EqualsAndHashCode(of = "id")
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "app_user",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_app_user_username", columnNames = "username"),
                @UniqueConstraint(name = "uk_app_user_email", columnNames = "email")
        },
        indexes = @Index(name = "idx_app_user_aktif", columnList = "aktif"))
public class AppUser {

    /** Birincil anahtar. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Kullanici adi (opsiyonel, unique — PostgreSQL'de null degerler unique ihlali yapmaz). */
    @Column(name = "username", nullable = true, length = 50)
    private String username;

    /** E-posta adresi. Giris icin kullanilir. */
    @Column(name = "email", length = 100)
    private String email;

    /** BCrypt ile hashlanmis sifre. */
    @Column(name = "password", nullable = false, length = 255)
    private String password;

    /** Kullanicinin ad soyad bilgisi. */
    @Column(name = "ad_soyad", length = 100)
    private String adSoyad;

    /** Kullanici rolu. {@link UserRoleEnum#ADMIN} veya {@link UserRoleEnum#USER}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private UserRoleEnum role;

    /** Erisim baslangic tarihi. {@code null} ise kisitlama yok. */
    @Column(name = "valid_from")
    private LocalDate validFrom;

    /** Erisim bitis tarihi. {@code null} ise kisitlama yok. */
    @Column(name = "valid_to")
    private LocalDate validTo;

    /** Aktif/pasif durumu. Soft delete icin kullanilir. */
    @Column(name = "aktif", nullable = false)
    private Boolean aktif = true;

    /** Kayit olusturma zamani. */
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    /** Son guncelleme zamani. */
    @Column(name = "update_time")
    private LocalDateTime updateTime;

    /**
     * Entity persist edilmeden once createTime alanini set eder.
     */
    @PrePersist
    protected void onCreate() {
        if (createTime == null) createTime = LocalDateTime.now();
    }

    /**
     * Entity guncellemeden once updateTime alanini set eder.
     */
    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}
