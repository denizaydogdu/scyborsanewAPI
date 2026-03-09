package com.scyborsa.api.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Araci kurum entity sinifi.
 *
 * <p>Borsa araci kurumlarini temsil eder. Fintables API'den gelen
 * brokerage bilgileri bu entity'ye sync edilir.</p>
 *
 * <p><b>Tablo:</b> {@code araci_kurum}</p>
 *
 * @see com.scyborsa.api.repository.AraciKurumRepository
 * @see com.scyborsa.api.service.AraciKurumService
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "araci_kurum",
        uniqueConstraints = @UniqueConstraint(name = "uk_araci_kurum_code", columnNames = "code"),
        indexes = @Index(name = "idx_araci_kurum_aktif", columnList = "aktif"))
public class AraciKurum {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Araci kurum kodu (unique). Fintables API'den gelir. Orn: "isyatirim", "garanti". */
    @Column(name = "code", nullable = false, length = 20)
    private String code;

    /** Araci kurum tam adi. Orn: "Is Yatirim Menkul Degerler A.S.". */
    @Column(name = "title", nullable = false, length = 150)
    private String title;

    /** Araci kurum kisa adi. Orn: "Is Yatirim". */
    @Column(name = "short_title", length = 50)
    private String shortTitle;

    /** Araci kurum logo URL'i. */
    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    /** Araci kurumun bagli oldugu halka acik sirket. Orn: "ISMEN", "GARAN". */
    @Column(name = "public_company", length = 20)
    private String publicCompany;

    /** Araci kurumun borsada islem gorup gormedigini belirtir. */
    @Column(name = "is_listed")
    private Boolean isListed;

    /** Araci kurumun aktif/pasif durumu. Soft delete icin kullanilir. */
    @Column(name = "aktif", nullable = false)
    private Boolean aktif = true;

    /** Listeleme sirasi. */
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
