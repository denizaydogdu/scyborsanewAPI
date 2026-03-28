package com.scyborsa.api.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Takip listesi hisse kalemi entity sinifi.
 *
 * <p>Bir takip listesine eklenen tek bir hisseyi temsil eder. Her kalem
 * hisse kodu, hisse adi ve siralama bilgisi icerir. Ayni listede ayni
 * hisse kodu tekrar edemez (unique constraint).</p>
 *
 * <p><b>Tablo:</b> {@code watchlist_item}</p>
 *
 * @see Watchlist
 * @see com.scyborsa.api.repository.WatchlistItemRepository
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "watchlist_item", indexes = {
        @Index(name = "idx_wl_item_watchlist", columnList = "watchlist_id"),
        @Index(name = "idx_wl_item_stock", columnList = "stock_code")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_watchlist_stock", columnNames = {"watchlist_id", "stock_code"})
})
public class WatchlistItem {

    /** Otomatik artan birincil anahtar. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Bu kalemin ait oldugu takip listesi. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "watchlist_id", nullable = false)
    private Watchlist watchlist;

    /** Hisse borsa kodu (orn. "THYAO"). */
    @Column(name = "stock_code", nullable = false, length = 10)
    private String stockCode;

    /** Hisse gorunen adi (orn. "Turk Hava Yollari"). */
    @Column(name = "stock_name", length = 100)
    private String stockName;

    /** Hissenin liste icindeki gorunum sirasi. */
    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;

    /** Kayit olusturma zamani. */
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    /**
     * Entity persist edilmeden once createTime alanini set eder.
     */
    @PrePersist
    protected void onCreate() {
        if (createTime == null) {
            createTime = LocalDateTime.now();
        }
    }
}
