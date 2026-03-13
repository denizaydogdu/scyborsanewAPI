package com.scyborsa.api.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * Hisse bilgilerinin saklandığı ana entity.
 * <p>
 * BIST'te işlem gören tüm hisselerin master data'sı. Hisse ekleme, yasaklama
 * ve aktiflik yönetimi bu entity üzerinden yapılır. Tablo adı: {@code stock_model}.
 * </p>
 *
 * @see com.scyborsa.api.repository.StockModelRepository
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "stock_model",
    indexes = {
        @Index(name = "idx_stock_name", columnList = "stock_name"),
        @Index(name = "idx_is_banned", columnList = "is_banned")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_stock_name", columnNames = "stock_name")
    }
)
public class StockModel {

    /** Otomatik artan birincil anahtar. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Hissenin borsa kodu (ör. "THYAO"). Benzersiz, boş olamaz, maks 20 karakter. */
    @Column(name = "stock_name", nullable = false, unique = true, length = 20)
    private String stockName;

    /** Hisse tipi kimliği (ör. hisse senedi, varant, ETF ayrımı). */
    @Column(name = "stock_type_id", nullable = false)
    private Long stockTypeId;

    /** Hissenin yasaklı (işlem dışı) olup olmadığı. {@code true} ise screener/analizlere dahil edilmez. */
    @Column(name = "is_banned", nullable = false)
    private Boolean isBanned = false;

    /** Yasaklama nedeni açıklaması. Sadece yasaklı hisselerde dolu olur, maks 200 karakter. */
    @Column(name = "banned_situation", length = 200)
    private String bannedSituation;

    /** Kaydın oluşturulma zamanı. {@link #onCreate()} ile otomatik atanır. */
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    /** Son güncelleme zamanı. {@link #onUpdate()} ile otomatik atanır. */
    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        if (createTime == null) createTime = LocalDateTime.now();
        if (isBanned == null) isBanned = false;
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }

    /**
     * Hissenin aktif (yasaklanmamis) olup olmadigini dondurur.
     *
     * @return {@code true} ise hisse aktif (yasakli degil)
     */
    public boolean isAktif() {
        return !Boolean.TRUE.equals(isBanned);
    }

    /**
     * Hisseyi yasaklar ve nedenini kaydeder.
     *
     * @param neden yasaklama nedeni aciklamasi
     */
    public void yasakla(String neden) {
        this.isBanned = true;
        this.bannedSituation = neden;
        this.updateTime = LocalDateTime.now();
    }

    /**
     * Hissenin yasagini kaldirir ve nedenini temizler.
     */
    public void yasakKaldir() {
        this.isBanned = false;
        this.bannedSituation = null;
        this.updateTime = LocalDateTime.now();
    }
}
