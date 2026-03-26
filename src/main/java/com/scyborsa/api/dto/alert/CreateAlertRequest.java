package com.scyborsa.api.dto.alert;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Fiyat alarmi olusturma istegi DTO'su.
 *
 * <p>Kullanicinin yeni bir fiyat alarmi olustururken gonderdigi verileri tasir.</p>
 *
 * @see com.scyborsa.api.service.alert.PriceAlertService#createAlert(Long, CreateAlertRequest)
 */
@Data
public class CreateAlertRequest {

    /** Hisse borsa kodu (orn. "THYAO"). Zorunlu. */
    @NotBlank
    private String stockCode;

    /** Hisse gorunen adi (opsiyonel). */
    private String stockName;

    /** Alarm yonu — "ABOVE" veya "BELOW". Zorunlu. */
    @NotNull
    @Pattern(regexp = "^(ABOVE|BELOW)$")
    private String direction;

    /** Hedef fiyat. Zorunlu, 0'dan buyuk olmali. */
    @NotNull
    @DecimalMin(value = "0.01")
    private Double targetPrice;

    /** Alarm olusturuldugundaki mevcut fiyat (opsiyonel). */
    private Double priceAtCreation;

    /** Kullanicinin opsiyonel notu (maks 200 karakter). */
    @Size(max = 200)
    private String note;
}
