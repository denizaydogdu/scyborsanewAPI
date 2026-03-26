package com.scyborsa.api.model;

import com.scyborsa.api.enums.AlertDirection;
import com.scyborsa.api.enums.AlertStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Fiyat alarmi entity sinifi.
 *
 * <p>Kullanicilarin belirledigi hedef fiyat alarmlarini temsil eder. Her alarm
 * bir hisse kodu, yon (ustte/altta), hedef fiyat ve durum bilgisi icerir.</p>
 *
 * <p><b>Tablo:</b> {@code price_alert}</p>
 *
 * @see com.scyborsa.api.enums.AlertDirection
 * @see com.scyborsa.api.enums.AlertStatus
 * @see com.scyborsa.api.repository.PriceAlertRepository
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "price_alert", indexes = {
        @Index(name = "idx_alert_user_status", columnList = "user_id, status"),
        @Index(name = "idx_alert_stock_status", columnList = "stock_code, status"),
        @Index(name = "idx_alert_user_read", columnList = "user_id, read_at")
})
public class PriceAlert {

    /** Otomatik artan birincil anahtar. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Alarmi olusturan kullanici. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    /** Kullanici email adresi (denormalize — lazy load onlemi, alarm motoru icin). */
    @Column(name = "user_email", length = 100)
    private String userEmail;

    /** Hisse borsa kodu (orn. "THYAO"). */
    @Column(name = "stock_code", nullable = false, length = 10)
    private String stockCode;

    /** Hisse gorunen adi (orn. "Turk Hava Yollari"). */
    @Column(name = "stock_name", length = 100)
    private String stockName;

    /** Alarm yonu — fiyat hedefin ustune mi altina mi dusecek. */
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    private AlertDirection direction;

    /** Kullanicinin belirledigi hedef fiyat. */
    @Column(name = "target_price", nullable = false)
    private Double targetPrice;

    /** Alarm olusturuldugu andaki hisse fiyati. */
    @Column(name = "price_at_creation")
    private Double priceAtCreation;

    /** Alarm durumu. Varsayilan {@link AlertStatus#ACTIVE}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    @Builder.Default
    private AlertStatus status = AlertStatus.ACTIVE;

    /** Alarm tetiklendigindeki gercek fiyat. */
    @Column(name = "trigger_price")
    private Double triggerPrice;

    /** Alarm tetiklenme zamani. */
    @Column(name = "triggered_at")
    private LocalDateTime triggeredAt;

    /** Kullanicinin bildirimi okudugu zaman. */
    @Column(name = "read_at")
    private LocalDateTime readAt;

    /** Kullanicinin opsiyonel notu (maks 200 karakter). */
    @Column(name = "note", length = 200)
    private String note;

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
