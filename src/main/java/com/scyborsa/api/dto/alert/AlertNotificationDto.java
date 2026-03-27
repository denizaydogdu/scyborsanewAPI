package com.scyborsa.api.dto.alert;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Fiyat alarmi bildirim DTO'su.
 *
 * <p>Tetiklenen bir alarm icin kullaniciya gonderilecek bildirim verilerini tasir.
 * WebSocket veya push notification kanallari uzerinden iletilebilir.</p>
 *
 * @see com.scyborsa.api.model.PriceAlert
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertNotificationDto {

    /** Alarm ID'si. */
    private Long alertId;

    /** Hisse borsa kodu. */
    private String stockCode;

    /** Hisse gorunen adi. */
    private String stockName;

    /** Alarm yonu ("ABOVE" veya "BELOW"). */
    private String direction;

    /** Hedef fiyat. */
    private Double targetPrice;

    /** Tetiklenme anindaki gercek fiyat. */
    private Double triggerPrice;

    /** Tetiklenme zamani. */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime triggeredAt;

    /** Kullaniciya gosterilecek mesaj (orn. "THYAO 315.50₺'ye ulasti"). */
    private String message;
}
