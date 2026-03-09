package com.scyborsa.api.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * Model portföy aracı kurum entity sınıfı.
 *
 * <p>BIST model portföylerinde takip edilen aracı kurumları temsil eder.
 * Her kurum için ad, logo, hisse sayısı ve sıralama bilgisi tutulur.</p>
 *
 * <p><b>Tablo:</b> {@code model_portfoy_kurum}</p>
 *
 * @see com.scyborsa.api.repository.ModelPortfoyKurumRepository
 * @see com.scyborsa.api.service.ModelPortfoyService
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "model_portfoy_kurum",
        uniqueConstraints = @UniqueConstraint(name = "uk_mpk_kurum_adi", columnNames = "kurum_adi"),
        indexes = @Index(name = "idx_mpk_aktif", columnList = "aktif"))
public class ModelPortfoyKurum {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Aracı kurum adı (unique). Örn: "Burgan", "Tacirler". */
    @Column(name = "kurum_adi", nullable = false, length = 100)
    private String kurumAdi;

    /** Kurum logo URL'i. Örn: "/assets/images/brokers/default-broker.png". */
    @Column(name = "logo_url", length = 255)
    private String logoUrl;

    /** Kurum model portföyündeki hisse sayısı. */
    @Column(name = "hisse_sayisi")
    private Integer hisseSayisi;

    /** Kurumun aktif/pasif durumu. Soft delete için kullanılır. */
    @Column(name = "aktif", nullable = false)
    private Boolean aktif = true;

    /** Kartların gösterim sırası. */
    @Column(name = "sira_no")
    private Integer siraNo;

    /** Kayıt oluşturma zamanı. */
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    /** Son güncelleme zamanı. */
    @Column(name = "update_time")
    private LocalDateTime updateTime;

    /**
     * Entity persist edilmeden önce createTime alanını set eder.
     */
    @PrePersist
    protected void onCreate() {
        if (createTime == null) createTime = LocalDateTime.now();
    }

    /**
     * Entity güncellemeden önce updateTime alanını set eder.
     */
    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}
