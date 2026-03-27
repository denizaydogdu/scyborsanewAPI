package com.scyborsa.api.dto.alert;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Fiyat alarmi veri transfer nesnesi.
 *
 * <p>Kullaniciya gonderilecek alarm bilgilerini tasir. {@code currentPrice} ve
 * {@code distancePercent} alanlari sorgu zamaninda zenginlestirilir (DB'de saklanmaz).</p>
 *
 * @see com.scyborsa.api.model.PriceAlert
 * @see com.scyborsa.api.service.alert.PriceAlertService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceAlertDto {

    /** Alarm ID'si. */
    private Long id;

    /** Hisse borsa kodu (orn. "THYAO"). */
    private String stockCode;

    /** Hisse gorunen adi. */
    private String stockName;

    /** Alarm yonu ("ABOVE" veya "BELOW"). */
    private String direction;

    /** Hedef fiyat. */
    private Double targetPrice;

    /** Alarm olusturuldugu andaki fiyat. */
    private Double priceAtCreation;

    /** Alarm durumu ("ACTIVE", "TRIGGERED", "CANCELLED", "EXPIRED"). */
    private String status;

    /** Tetiklenme anindaki gercek fiyat. */
    private Double triggerPrice;

    /** Tetiklenme zamani. */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime triggeredAt;

    /** Alarm iptal edilme zamani. */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime cancelledAt;

    /** Kullanicinin bildirimi okudugu zaman. */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime readAt;

    /** Kullanicinin opsiyonel notu. */
    private String note;

    /** Kayit olusturma zamani. */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createTime;

    /** Anlik fiyat (sorgu zamaninda zenginlestirilir, DB'de saklanmaz). */
    private Double currentPrice;

    /** Hedef fiyata uzaklik yuzdesi (sorgu zamaninda hesaplanir). */
    private Double distancePercent;

    /** Alarmi olusturan kullanicinin email adresi. */
    private String userEmail;
}
