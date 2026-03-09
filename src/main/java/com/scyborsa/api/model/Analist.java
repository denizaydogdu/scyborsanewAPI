package com.scyborsa.api.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * Analist entity sinifi.
 *
 * <p>Borsa analistlerini temsil eder. Her analist icin ad, unvan, resim,
 * hisse onerisi, kazanc, trend bilgisi ve chart verisi tutulur.</p>
 *
 * <p><b>Tablo:</b> {@code analist}</p>
 *
 * @see com.scyborsa.api.repository.AnalistRepository
 * @see com.scyborsa.api.service.AnalistService
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "analist",
        uniqueConstraints = @UniqueConstraint(name = "uk_analist_ad", columnNames = "ad"),
        indexes = @Index(name = "idx_analist_aktif", columnList = "aktif"))
public class Analist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Analist adi (unique). Orn: "Seyda Hoca", "Ozkan Filiz". */
    @Column(name = "ad", nullable = false, length = 100)
    private String ad;

    /** Analist unvani. Orn: "Velzon", "Yatirim Enstitusu". */
    @Column(name = "unvan", length = 150)
    private String unvan;

    /** Analist resim URL'i. Orn: "/assets/images/companies/img-1.png". */
    @Column(name = "resim_url", length = 255)
    private String resimUrl;

    /** Analistin toplam hisse onerisi sayisi. */
    @Column(name = "hisse_onerisi")
    private Integer hisseOnerisi;

    /** Analistin toplam kazanc tutari. */
    @Column(name = "kazanc")
    private Integer kazanc;

    /** Kazanc trendi. {@code true} ise yukselis, {@code false} ise dusus. */
    @Column(name = "trend", nullable = false)
    private Boolean trend = false;

    /** Chart renk kodu. Orn: "danger", "success", "warning". */
    @Column(name = "chart_renk", length = 20)
    private String chartRenk;

    /** Chart verisi — JSON array formati. Orn: "[12,14,2,47,42,15,47,75,65,19,14]". */
    @Column(name = "chart_verisi", columnDefinition = "TEXT")
    private String chartVerisi;

    /** Analistin aktif/pasif durumu. Soft delete icin kullanilir. */
    @Column(name = "aktif", nullable = false)
    private Boolean aktif = true;

    /** Kartlarin gosterim sirasi. */
    @Column(name = "sira_no")
    private Integer siraNo;

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
