package com.scyborsa.api.model;

import com.scyborsa.api.enums.YatirimVadesi;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Takip hissesi (hisse onerisi) entity sinifi.
 *
 * <p>Admin backoffice'den eklenen kisa/orta/uzun vadeli hisse onerilerini temsil eder.
 * Her oneri icin giris fiyati, hedef fiyat, zarar durdur ve vade bilgisi tutulur.</p>
 *
 * <p><b>Tablo:</b> {@code takip_hissesi}</p>
 *
 * @see com.scyborsa.api.repository.TakipHissesiRepository
 * @see com.scyborsa.api.service.TakipHissesiService
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "takip_hissesi",
        indexes = {
                @Index(name = "idx_th_aktif", columnList = "aktif"),
                @Index(name = "idx_th_vade", columnList = "vade"),
                @Index(name = "idx_th_hisse_kodu", columnList = "hisse_kodu")
        })
public class TakipHissesi {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Hisse borsa kodu. Orn: "THYAO". */
    @Column(name = "hisse_kodu", nullable = false, length = 10)
    private String hisseKodu;

    /** Hisse gorunen adi. Orn: "Turk Hava Yollari". */
    @Column(name = "hisse_adi", length = 100)
    private String hisseAdi;

    /** Yatirim vadesi (KISA_VADE, ORTA_VADE, UZUN_VADE). */
    @Enumerated(EnumType.STRING)
    @Column(name = "vade", nullable = false, length = 20)
    private YatirimVadesi vade;

    /** Oneri zamanindaki giris fiyati. */
    @Column(name = "giris_fiyati", nullable = false)
    private Double girisFiyati;

    /** Onerinin yapildigi tarih. */
    @Column(name = "giris_tarihi", nullable = false)
    private LocalDate girisTarihi;

    /** Hedef fiyat (opsiyonel). */
    @Column(name = "hedef_fiyat")
    private Double hedefFiyat;

    /** Zarar durdur (stop loss) fiyati (opsiyonel). */
    @Column(name = "zarar_durdur")
    private Double zararDurdur;

    /** Gerçek maliyet (alış) fiyatı (opsiyonel). */
    @Column(name = "maliyet_fiyati")
    private Double maliyetFiyati;

    /** Oneri aciklamasi / sebep (opsiyonel, maks 500 karakter). */
    @Column(name = "not_aciklama", length = 500)
    private String notAciklama;

    /** Yüklenen resim dosya adı (opsiyonel). */
    @Column(name = "resim_url", length = 255)
    private String resimUrl;

    /** Aktif/pasif durumu. Soft delete için kullanılır. */
    @Column(name = "aktif", nullable = false)
    @Builder.Default
    private Boolean aktif = true;

    /** Gosterim sirasi. */
    @Column(name = "sira_no")
    private Integer siraNo;

    /** Kayit olusturma zamani. */
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    /** Son guncelleme zamani. */
    @Column(name = "update_time")
    private LocalDateTime updateTime;

    /**
     * Entity persist edilmeden once createTime ve aktif alanlarini set eder.
     */
    @PrePersist
    protected void onCreate() {
        if (createTime == null) createTime = LocalDateTime.now();
        if (aktif == null) aktif = true;
    }

    /**
     * Entity guncellemeden once updateTime alanini set eder.
     */
    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}
