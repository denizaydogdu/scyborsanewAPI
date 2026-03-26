package com.scyborsa.api.dto.market;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;
import java.util.Map;

/**
 * Mum formasyonu tarama sonucu DTO'su.
 *
 * <p>Bir hisse icin tespit edilen mum formasyonlarini icerir.
 * TradingView candlestick pattern screener verisinden parse edilir.</p>
 *
 * @see com.scyborsa.api.service.market.CandlePatternService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandlePatternStockDto {

    /** Hisse kodu (orn. "THYAO"). */
    private String symbol;

    /** TradingView logo ID. */
    private String logoid;

    /** Tespit edilen formasyon ID listesi (orn. ["Hammer", "MorningStar"]). */
    private List<String> patterns;

    /** Tespit edilen formasyon sayisi. */
    private int patternCount;

    /** Formasyon deger eslesmesi (orn. {"Hammer": 1, "Doji": 0}). */
    private Map<String, Integer> patternValues;

    /** Guncel fiyat (TL). */
    private Double price;

    /** Gunluk degisim yuzdesi. */
    private Double changePercent;

    /** Islem hacmi. */
    private Double volume;

    /** Gunluk acilis fiyati (TL). */
    private Double open;

    /** Katilim endeksi uyesi mi? */
    @JsonProperty("katilim")
    private boolean katilim;
}
