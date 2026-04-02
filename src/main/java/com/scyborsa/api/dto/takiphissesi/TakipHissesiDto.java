package com.scyborsa.api.dto.takiphissesi;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.scyborsa.api.enums.YatirimVadesi;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Takip hissesi veri transfer nesnesi.
 *
 * <p>Kullaniciya gonderilecek hisse onerisi bilgilerini tasir.
 * {@code guncelFiyat}, {@code getiriYuzde}, {@code hedefUlasildi} ve
 * {@code zararDurdurUlasildi} alanlari sorgu zamaninda zenginlestirilir
 * (DB'de saklanmaz).</p>
 *
 * @see com.scyborsa.api.model.TakipHissesi
 * @see com.scyborsa.api.service.TakipHissesiService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TakipHissesiDto {

    /** Kayit ID'si. */
    private Long id;

    /** Hisse borsa kodu (orn. "THYAO"). */
    private String hisseKodu;

    /** Hisse gorunen adi. */
    private String hisseAdi;

    /** Yatirim vadesi. */
    private YatirimVadesi vade;

    /** Oneri zamanindaki giris fiyati. */
    private Double girisFiyati;

    /** Onerinin yapildigi tarih. */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate girisTarihi;

    /** Hedef fiyat. */
    private Double hedefFiyat;

    /** Zarar durdur (stop loss) fiyati. */
    private Double zararDurdur;

    /** Gerçek maliyet (alış) fiyatı. */
    private Double maliyetFiyati;

    /** Yüklenen resim dosya adı. */
    private String resimUrl;

    /** Oneri aciklamasi / sebep. */
    private String notAciklama;

    /** Aktif/pasif durumu. */
    private Boolean aktif;

    /** Gosterim sirasi. */
    private Integer siraNo;

    /** Kayit olusturma zamani. */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createTime;

    /** Son guncelleme zamani. */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updateTime;

    // ==================== ENRICHED FIELDS (DB'de saklanmaz) ====================

    /** Anlik guncel fiyat (sorgu zamaninda QuotePriceCache'ten zenginlestirilir). */
    private Double guncelFiyat;

    /** Getiri yuzdesi: ((guncelFiyat - girisFiyati) / girisFiyati) * 100. */
    private Double getiriYuzde;

    /** Maliyet bazlı getiri yüzdesi. */
    private Double maliyetGetiriYuzde;

    /** Guncel fiyat hedef fiyata ulasti mi. */
    private Boolean hedefUlasildi;

    /** Guncel fiyat zarar durdur seviyesine ulasti mi. */
    private Boolean zararDurdurUlasildi;
}
