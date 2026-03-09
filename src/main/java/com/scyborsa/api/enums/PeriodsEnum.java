package com.scyborsa.api.enums;

import lombok.Getter;

/**
 * Tarama periyotlarini tanimlayan enum.
 *
 * <p>Her periyot bir goruntuleme adi ({@code name}) ve TradingView resolution kodu
 * ({@code resolution}) icerir.</p>
 *
 * <p>Ornek resolution kodlari:</p>
 * <ul>
 *   <li>{@code "15"} - 15 dakikalik mum</li>
 *   <li>{@code "30"} - 30 dakikalik mum</li>
 *   <li>{@code "60"} - 1 saatlik mum</li>
 *   <li>{@code "1D"} - Gunluk mum</li>
 *   <li>{@code "1W"} - Haftalik mum</li>
 * </ul>
 *
 * <p>{@code NO_TIME} ve {@code NO_TIME_CHART} degerleri zamanla iliskilendirilmeyen
 * tarama/gosterge islemleri icin kullanilir.</p>
 */
@Getter
public enum PeriodsEnum {
    FIFTEEN_MINUTES("15 DAKIKALIK TARAMA", "15"),
    THIRTY_MINUTES("30 DAKIKALIK TARAMA", "30"),
    ONE_HOUR("1 SAATLIK TARAMA", "60"),
    FOUR_HOURS("4 SAATLIK TARAMA", "240"),
    DAILY("GUNLUK TARAMA", "1D"),
    WEEK("HAFTALIK TARAMA", "1W"),
    NO_TIME("NO_TIME", "NO_TIME"),
    NO_TIME_CHART("NO_TIME_CHART", "NO_TIME_CHART");

    private final String name;
    private final String resolution;

    PeriodsEnum(String name, String resolution) {
        this.name = name;
        this.resolution = resolution;
    }
}
