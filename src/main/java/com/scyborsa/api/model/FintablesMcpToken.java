package com.scyborsa.api.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Fintables MCP OAuth 2.0 access token'ini veritabanında saklayan entity.
 *
 * <p>Server restart'larda token kaybolmasını önler. Tek satırlık tablo,
 * {@code tokenKey} = "MCP_ACCESS_TOKEN" unique constraint ile korunur.</p>
 *
 * @see com.scyborsa.api.service.client.FintablesMcpTokenStore
 */
@Entity
@Table(name = "fintables_mcp_token")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FintablesMcpToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Token anahtarı. Default: "MCP_ACCESS_TOKEN". */
    @Column(name = "token_key", unique = true, nullable = false, length = 50)
    @Builder.Default
    private String tokenKey = "MCP_ACCESS_TOKEN";

    /** OAuth 2.0 access token değeri. */
    @Column(name = "access_token", columnDefinition = "TEXT")
    private String accessToken;

    /** Token'in geçerlilik bitiş zamanı (UTC). */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** Kaydın oluşturulma zamanı. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Kaydın son güncelleme zamanı. */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Persist öncesi otomatik zaman damgası atar.
     */
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    /**
     * Update öncesi otomatik güncelleme zamanı atar.
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
