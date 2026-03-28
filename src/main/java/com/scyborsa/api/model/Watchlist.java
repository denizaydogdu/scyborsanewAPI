package com.scyborsa.api.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Takip listesi entity sinifi.
 *
 * <p>Kullanicilarin olusturdugu hisse takip listelerini temsil eder. Her kullanici
 * birden fazla takip listesi olusturabilir. Bir liste varsayilan (default) olarak
 * isaretlenebilir ve soft delete ile pasif hale getirilebilir.</p>
 *
 * <p><b>Tablo:</b> {@code watchlist}</p>
 *
 * @see WatchlistItem
 * @see com.scyborsa.api.repository.WatchlistRepository
 */
@Getter
@Setter
@ToString(exclude = "items")
@EqualsAndHashCode(of = "id")
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "watchlist", indexes = {
        @Index(name = "idx_watchlist_user_aktif", columnList = "user_id, aktif"),
        @Index(name = "idx_watchlist_user_default", columnList = "user_id, is_default")
})
public class Watchlist {

    /** Otomatik artan birincil anahtar. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Listeyi olusturan kullanici. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    /** Kullanici email adresi (denormalize — broadcast hizi icin). */
    @Column(name = "user_email", length = 100)
    private String userEmail;

    /** Liste adi (maks 50 karakter). */
    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /** Opsiyonel liste aciklamasi (maks 200 karakter). */
    @Column(name = "description", length = 200)
    private String description;

    /** Listelerin gorunum sirasi. */
    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;

    /** Varsayilan liste mi — her kullanicinin tek varsayilan listesi olabilir. */
    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private Boolean isDefault = false;

    /** Aktiflik durumu — soft delete icin kullanilir. */
    @Column(name = "aktif", nullable = false)
    @Builder.Default
    private Boolean aktif = true;

    /** Kayit olusturma zamani. */
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    /** Son guncelleme zamani. */
    @Column(name = "update_time")
    private LocalDateTime updateTime;

    /** Listedeki hisse kalemleri (siralamaya gore). */
    @OneToMany(mappedBy = "watchlist", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    @Builder.Default
    private List<WatchlistItem> items = new ArrayList<>();

    /**
     * Entity persist edilmeden once createTime alanini set eder.
     */
    @PrePersist
    protected void onCreate() {
        if (createTime == null) {
            createTime = LocalDateTime.now();
        }
    }

    /**
     * Entity guncellenmeden once updateTime alanini set eder.
     */
    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}
