package com.scyborsa.api.enums;

/**
 * TradingView Pine Screener'da kullanilan ozel gosterge (indicator) tanimlari.
 *
 * <p>Her enum degeri, TradingView screener API'sine gonderilen
 * ozel Pine Script gostergesini temsil eder.</p>
 *
 * <ul>
 *   <li>{@link #VELZON_MA} - Velzon hareketli ortalama gostergesi</li>
 * </ul>
 */
public enum PineScreenerIndicatorEnum {

    /** Velzon ozel hareketli ortalama (Moving Average) Pine Script gostergesi. */
    VELZON_MA
}
