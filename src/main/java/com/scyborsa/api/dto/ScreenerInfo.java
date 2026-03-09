package com.scyborsa.api.dto;

import java.time.LocalTime;

/**
 * Tarama sonucu ozet bilgisi. Hisse gruplama islemlerinde kullanilir.
 *
 * <p>Bir hissenin belirli bir taramadaki temel bilgilerini tasir:
 * tarama adi, zamani, fiyat ve degisim yuzdesi.</p>
 *
 * @param screenerName tarama adi (orn. "MIX TARAMASI")
 * @param screenerTime tarama zamani (orn. 10:30)
 * @param price tarama anindaki hisse fiyati
 * @param changePercent degisim yuzdesi (orn. 3.5 = %3.5)
 */
public record ScreenerInfo(
    String screenerName,
    LocalTime screenerTime,
    Double price,
    Double changePercent
) {}
