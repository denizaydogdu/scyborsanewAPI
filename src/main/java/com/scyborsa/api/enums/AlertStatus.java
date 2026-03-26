package com.scyborsa.api.enums;

/**
 * Fiyat alarmi durum bilgisi.
 *
 * <p>Bir fiyat alarminin yasam dongusu boyunca gecebilecegi durumlari tanimlar.</p>
 *
 * @see com.scyborsa.api.model.PriceAlert
 */
public enum AlertStatus {

    /** Alarm aktif — fiyat izleniyor. */
    ACTIVE,

    /** Alarm tetiklendi — hedef fiyata ulasildi. */
    TRIGGERED,

    /** Alarm kullanici tarafindan iptal edildi. */
    CANCELLED,

    /** Alarm suresi doldu (ileride kullanilmak uzere). */
    EXPIRED
}
