package com.scyborsa.api.dto.takiphissesi;

import com.scyborsa.api.enums.YatirimVadesi;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * Takip hissesi olusturma/guncelleme istegi DTO'su.
 *
 * <p>Admin backoffice'den yeni hisse onerisi eklerken veya mevcut oneriyi
 * guncellerken gonderilen verileri tasir.</p>
 *
 * @see com.scyborsa.api.service.TakipHissesiService#createTakipHissesi(TakipHissesiRequest)
 * @see com.scyborsa.api.service.TakipHissesiService#updateTakipHissesi(Long, TakipHissesiRequest)
 */
@Data
public class TakipHissesiRequest {

    /** Hisse borsa kodu (örn. "THYAO"). Zorunlu, maks 10 karakter. */
    @NotBlank
    @Size(max = 10)
    private String hisseKodu;

    /** Hisse görünen adı (opsiyonel, maks 100 karakter). */
    @Size(max = 100)
    private String hisseAdi;

    /** Yatirim vadesi. Zorunlu. */
    @NotNull
    private YatirimVadesi vade;

    /** Oneri zamanindaki giris fiyati. Zorunlu, 0.01'den buyuk olmali. */
    @NotNull
    @DecimalMin(value = "0.01")
    private Double girisFiyati;

    /** Onerinin yapildigi tarih. Zorunlu. */
    @NotNull
    private LocalDate girisTarihi;

    /** Hedef fiyat (opsiyonel). 0.01'den buyuk olmali. */
    @DecimalMin(value = "0.01")
    private Double hedefFiyat;

    /** Zarar durdur (stop loss) fiyati (opsiyonel). 0.01'den buyuk olmali. */
    @DecimalMin(value = "0.01")
    private Double zararDurdur;

    /** Gerçek maliyet (alış) fiyatı (opsiyonel). */
    @DecimalMin(value = "0.01")
    private Double maliyetFiyati;

    /** Oneri aciklamasi / sebep (opsiyonel, maks 500 karakter). */
    @Size(max = 500)
    private String notAciklama;

    /** Gosterim sirasi (opsiyonel). */
    private Integer siraNo;
}
