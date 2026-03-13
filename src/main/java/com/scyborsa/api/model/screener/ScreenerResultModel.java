package com.scyborsa.api.model.screener;

import com.scyborsa.api.enums.ScreenerTypeEnum;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Tarama sonuçlarının saklandığı entity.
 *
 * <p>TradingView tarama sonuçlarını PostgreSQL'de saklar. Her bir tarama sonucu
 * (hisse adı, fiyat, değişim yüzdesi, TP/SL hedefleri vb.) bir kayıt olarak tutulur.</p>
 *
 * <p>Tablo: {@code screener_result}</p>
 *
 * @see com.scyborsa.api.repository.ScreenerResultRepository
 * @see com.scyborsa.api.enums.ScreenerTypeEnum
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
@Entity
@Table(
    name = "screener_result",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_sr_stock_day_time_name",
            columnNames = {"stock_name", "screener_day", "screener_time", "screener_name"})
    },
    indexes = {
        @Index(name = "idx_sr_stock_day", columnList = "stock_name, screener_day"),
        @Index(name = "idx_sr_day_time_type", columnList = "screener_day, screener_time, screener_type"),
        @Index(name = "idx_sr_tp_pending", columnList = "tp_check_done, tp_price"),
        @Index(name = "idx_sr_sl_pending", columnList = "sl_check_done, sl_price"),
        @Index(name = "idx_sr_telegram_unsent", columnList = "screener_day, telegram_sent")
    }
)
public class ScreenerResultModel {

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

    /** Tarama anındaki hisse fiyatı. */
    @Column(name = "price")
    private Double price;

    /** Değişim yüzdesi (ör. 3.5 = %3.5 artış). */
    @Column(name = "percentage")
    private Double percentage;

    // ==================== GÜN SONU VERİLERİ ====================

    /** Tarama günü kapanış fiyatı. Gün sonu job'u tarafından güncellenir. */
    @Column(name = "gun_sonu_fiyati")
    private Double gunSonuFiyati;

    /** Gün sonu değişim yüzdesi: (kapanış - giriş) / giriş × 100. */
    @Column(name = "gun_sonu_degisim")
    private Double gunSonuDegisim;

    // ==================== TARAMA BİLGİLERİ ====================

    /** Tarama zamanı (ör. 10:30). */
    @Column(name = "screener_time")
    private LocalTime screenerTime;

    /** Tarama günü. */
    @Column(name = "screener_day")
    private LocalDate screenerDay;

    /** Tarama stratejisi adı (ör. "BIST_VEGAS"). */
    @Column(name = "screener_name", length = 100)
    private String screenerName;

    /** Tarama türü. */
    @Enumerated(EnumType.STRING)
    @Column(name = "screener_type", length = 50)
    private ScreenerTypeEnum screenerType;

    /** Alt tarama adı (sub-scan). Ana kategori altındaki spesifik strateji. */
    @Column(name = "sub_scan_name", length = 100)
    private String subScanName;

    // ==================== ZAMAN BİLGİLERİ ====================

    /** Sisteme kayıt zamanı. {@link #onCreate()} ile otomatik atanır. */
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    /** SL/TP tetikleme zamanı. */
    @Column(name = "trigger_time")
    private LocalDateTime triggerTime;

    // ==================== TP / SL ====================

    /** Take Profit hedef fiyatı. */
    @Column(name = "tp_price")
    private Double tpPrice;

    /** Stop Loss hedef fiyatı. */
    @Column(name = "sl_price")
    private Double slPrice;

    /** Take Profit kontrolü yapıldı mı? */
    @Column(name = "tp_check_done", nullable = false)
    private Boolean tpCheckDone = false;

    /** Stop Loss kontrolü yapıldı mı? */
    @Column(name = "sl_check_done", nullable = false)
    private Boolean slCheckDone = false;

    /** TP tetiklendiğindeki gerçek fiyat. */
    @Column(name = "tp_hit_price")
    private Double tpHitPrice;

    /** SL tetiklendiğindeki gerçek fiyat. */
    @Column(name = "sl_hit_price")
    private Double slHitPrice;

    /** TP tetiklenme zamanı. */
    @Column(name = "tp_hit_time")
    private LocalDateTime tpHitTime;

    /** SL tetiklenme zamanı. */
    @Column(name = "sl_hit_time")
    private LocalDateTime slHitTime;

    // ==================== TELEGRAM ====================

    /** Telegram'a gönderildi mi? */
    @Column(name = "telegram_sent", nullable = false)
    private Boolean telegramSent = false;

    /** Telegram gönderim zamanı. */
    @Column(name = "telegram_sent_time")
    private LocalDateTime telegramSentTime;

    /** TP için Telegram bildirimi gönderildi mi? */
    @Column(name = "tp_telegram_sent", nullable = false)
    private Boolean tpTelegramSent = false;

    /** SL için Telegram bildirimi gönderildi mi? */
    @Column(name = "sl_telegram_sent", nullable = false)
    private Boolean slTelegramSent = false;

    /** Kaç taramada ortak çıktı (grouped mesajlar için). */
    @Column(name = "screener_count")
    private Integer screenerCount;

    /** Ortak tarama adları (CSV: "MIX,MACD,VELZON"). */
    @Column(name = "common_screener_names", length = 500)
    private String commonScreenerNames;

    // ==================== LIFECYCLE ====================

    /** Entity persist edilmeden önce default değerleri atar. */
    @PrePersist
    protected void onCreate() {
        if (createTime == null) {
            createTime = ZonedDateTime.now(ISTANBUL_ZONE).toLocalDateTime();
        }
        if (slCheckDone == null) slCheckDone = false;
        if (tpCheckDone == null) tpCheckDone = false;
        if (tpTelegramSent == null) tpTelegramSent = false;
        if (slTelegramSent == null) slTelegramSent = false;
        if (telegramSent == null) telegramSent = false;
    }

    // ==================== UTILITY METHODS ====================

    /**
     * Pozitif değişim var mı?
     *
     * @return {@code true} ise yükseliş var
     */
    public boolean isPozitifDegisim() {
        return percentage != null && percentage > 0;
    }

    /**
     * TP fiyatına ulaşıldı mı kontrol eder.
     *
     * @param currentPrice mevcut fiyat
     * @return {@code true} ise TP tetiklendi
     */
    public boolean isTpReached(Double currentPrice) {
        return tpPrice != null && currentPrice != null && currentPrice >= tpPrice;
    }

    /**
     * SL fiyatına ulaşıldı mı kontrol eder.
     *
     * @param currentPrice mevcut fiyat
     * @return {@code true} ise SL tetiklendi
     */
    public boolean isSlReached(Double currentPrice) {
        return slPrice != null && currentPrice != null && currentPrice <= slPrice;
    }

    /**
     * TP tetiklendiğini kaydeder.
     *
     * @param currentPrice tetikleme anındaki fiyat
     * @throws IllegalArgumentException currentPrice null veya &lt;= 0 ise
     * @throws IllegalStateException TP zaten tetiklenmişse
     */
    public void markTpTriggered(Double currentPrice) {
        if (currentPrice == null || currentPrice <= 0) {
            throw new IllegalArgumentException("Geçersiz fiyat: " + currentPrice);
        }
        if (Boolean.TRUE.equals(this.tpCheckDone)) {
            throw new IllegalStateException("TP zaten tetiklendi: " + this.stockName);
        }
        this.tpCheckDone = true;
        this.tpHitPrice = currentPrice;
        LocalDateTime now = ZonedDateTime.now(ISTANBUL_ZONE).toLocalDateTime();
        this.tpHitTime = now;
        if (this.triggerTime == null) {
            this.triggerTime = now;
        }
    }

    /**
     * SL tetiklendiğini kaydeder.
     *
     * @param currentPrice tetikleme anındaki fiyat
     * @throws IllegalArgumentException currentPrice null veya &lt;= 0 ise
     * @throws IllegalStateException SL zaten tetiklenmişse
     */
    public void markSlTriggered(Double currentPrice) {
        if (currentPrice == null || currentPrice <= 0) {
            throw new IllegalArgumentException("Geçersiz fiyat: " + currentPrice);
        }
        if (Boolean.TRUE.equals(this.slCheckDone)) {
            throw new IllegalStateException("SL zaten tetiklendi: " + this.stockName);
        }
        this.slCheckDone = true;
        this.slHitPrice = currentPrice;
        LocalDateTime now = ZonedDateTime.now(ISTANBUL_ZONE).toLocalDateTime();
        this.slHitTime = now;
        if (this.triggerTime == null) {
            this.triggerTime = now;
        }
    }

    /**
     * Gün sonu fiyatını ve değişim yüzdesini ayarlar.
     *
     * @param closingPrice kapanış fiyatı
     */
    public void setGunSonuVerileri(Double closingPrice) {
        this.gunSonuFiyati = closingPrice;
        if (closingPrice != null && this.price != null && this.price > 0) {
            this.gunSonuDegisim = ((closingPrice - this.price) / this.price) * 100;
        }
    }

    /**
     * Kaydi Telegram'a gonderildi olarak isaretler (tekil kayitlar icin).
     * Gonderim zamanini Istanbul timezone'una gore atar.
     *
     * <p><b>Not:</b> Toplu gonderimde {@code screenerCount} ve {@code commonScreenerNames}
     * field'lari da set edilmeli — bunun icin
     * {@link com.scyborsa.api.repository.ScreenerResultRepository#bulkMarkTelegramSent}
     * tercih edilmelidir.</p>
     *
     * @see com.scyborsa.api.repository.ScreenerResultRepository#bulkMarkTelegramSent
     */
    public void markTelegramSent() {
        this.telegramSent = true;
        this.telegramSentTime = ZonedDateTime.now(ISTANBUL_ZONE).toLocalDateTime();
    }

    @Override
    public String toString() {
        return "ScreenerResultModel{id=" + id +
                ", stockName='" + stockName + '\'' +
                ", price=" + price +
                ", percentage=" + percentage +
                ", screenerTime=" + screenerTime +
                ", screenerDay=" + screenerDay +
                ", screenerName='" + screenerName + '\'' +
                ", screenerType=" + screenerType + '}';
    }
}
