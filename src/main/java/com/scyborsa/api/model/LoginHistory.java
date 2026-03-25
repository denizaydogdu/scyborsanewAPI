package com.scyborsa.api.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Giris gecmisi entity sinifi.
 *
 * <p>Her giris denemesini (basarili veya basarisiz) IP adresi,
 * user-agent ve sonuc bilgisi ile kaydeder.</p>
 *
 * <p><b>Tablo:</b> {@code login_history}</p>
 *
 * @see com.scyborsa.api.repository.LoginHistoryRepository
 * @see com.scyborsa.api.service.UserService
 */
@Entity
@Table(name = "login_history",
        indexes = {
                @Index(name = "idx_login_history_user_id", columnList = "user_id"),
                @Index(name = "idx_login_history_login_date", columnList = "login_date")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginHistory {

    /** Birincil anahtar. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Giris yapan kullanici. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private AppUser appUser;

    /** Giris denemesi zamani. */
    @Column(name = "login_date", nullable = false)
    private LocalDateTime loginDate;

    /** IP adresi (IPv6 icin 45 karakter). */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    /** Tarayici/istemci bilgisi. */
    @Column(name = "user_agent", length = 500)
    private String userAgent;

    /** Giris basarili mi? */
    @Column(name = "success", nullable = false)
    private boolean success;

    /** Basarisizlik nedeni (EXPIRED, DISABLED, BAD_CREDENTIALS vb.). */
    @Column(name = "failure_reason", length = 100)
    private String failureReason;

    /**
     * Entity persist edilmeden once loginDate alanini set eder.
     */
    @PrePersist
    protected void onCreate() {
        if (loginDate == null) loginDate = LocalDateTime.now();
    }
}
