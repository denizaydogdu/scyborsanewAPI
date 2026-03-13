package com.scyborsa.api.model.screener;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Tarama sonuçlarının sonraki günlerdeki performans takibini yapan entity.
 *
 * <p>Tarama sonuçlarında çıkan hisselerin ertesi günlerdeki OHLC verileri
 * ve hacim bilgisi saklanır. Tablo: {@code screener_result_tracker}</p>
 *
 * @see ScreenerResultModel
 * @see com.scyborsa.api.repository.ScreenerResultTrackerRepository
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
@Entity
@Table(
    name = "screener_result_tracker",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_srt_stock_screener_scan",
            columnNames = {"stock_name", "screener_day", "scan_day"})
    },
    indexes = {
        @Index(name = "idx_srt_stock_screener_day", columnList = "stock_name, screener_day"),
        @Index(name = "idx_srt_scan_day", columnList = "scan_day")
    }
)
public class ScreenerResultTrackerModel {

    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");

    // ==================== PRIMARY KEY ====================

    /** Otomatik artan birincil anahtar. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ==================== HİSSE BİLGİLERİ ====================

    /** Hisse borsa kodu (ör. "THYAO"). */
    @Column(name = "stock_name", nullable = false, length = 20)
    private String stockName;

    // ==================== OHLC VERİLERİ ====================

    /** Kapanış fiyatı. Kolon: {@code close_price} (SQL reserved word). */
    @Column(name = "close_price", precision = 10, scale = 2)
    private BigDecimal close;

    /** Açılış fiyatı. Kolon: {@code open_price} (SQL reserved word). */
    @Column(name = "open_price", precision = 10, scale = 2)
    private BigDecimal open;

    /** En yüksek fiyat. */
    @Column(name = "high_price", precision = 10, scale = 2)
    private BigDecimal high;

    /** En düşük fiyat. */
    @Column(name = "low_price", precision = 10, scale = 2)
    private BigDecimal low;

    // ==================== HACİM BİLGİLERİ ====================

    /** İşlem hacmi (lot). */
    @Column(name = "volume")
    private Long volume;

    /** İşlem adedi. */
    @Column(name = "amount")
    private Long amount;

    // ==================== TARİH BİLGİLERİ ====================

    /** Tarama yapıldığı gün. */
    @Column(name = "screener_day", nullable = false)
    private LocalDate screenerDay;

    /** Bu verinin ait olduğu takip günü. */
    @Column(name = "scan_day", nullable = false)
    private LocalDate scanDay;

    /** Sisteme kayıt zamanı. */
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    // ==================== LIFECYCLE ====================

    /** Entity persist edilmeden önce createTime'ı ayarlar. */
    @PrePersist
    protected void onCreate() {
        if (createTime == null) {
            createTime = ZonedDateTime.now(ISTANBUL_ZONE).toLocalDateTime();
        }
    }

    // ==================== UTILITY METHODS ====================

    /**
     * Pozitif gün mü? (close &gt; open)
     *
     * @return {@code true} ise pozitif kapandı
     */
    public boolean isPozitifGun() {
        return close != null && open != null && close.compareTo(open) > 0;
    }

    /**
     * Değişim yüzdesini hesaplar: (close - open) / open × 100.
     *
     * @return değişim yüzdesi; hesaplanamıyorsa {@link BigDecimal#ZERO}
     */
    public BigDecimal getDegisimYuzdesi() {
        if (open == null || close == null || open.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return close.subtract(open)
                .divide(open, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    /**
     * Günlük fiyat aralığını döndürür (high - low).
     *
     * @return fiyat aralığı; hesaplanamıyorsa {@link BigDecimal#ZERO}
     */
    public BigDecimal getFiyatAraligi() {
        if (high == null || low == null) {
            return BigDecimal.ZERO;
        }
        return high.subtract(low);
    }

    @Override
    public String toString() {
        return "ScreenerResultTrackerModel{" +
                "id=" + id +
                ", stockName='" + stockName + '\'' +
                ", close=" + close +
                ", open=" + open +
                ", screenerDay=" + screenerDay +
                ", scanDay=" + scanDay +
                '}';
    }
}
