package com.scyborsa.api.dto.screener;

import lombok.Data;

import java.util.List;

/**
 * TradingView Scanner API response DTO.
 *
 * <p>POST {@code scanner.tradingview.com/turkey/scan} endpoint'inin
 * JSON response'unu parse etmek icin kullanilir.</p>
 *
 * <h3>Ornek Response Yapisi:</h3>
 * <pre>{@code
 * {
 *   "totalCount": 5,
 *   "data": [
 *     {
 *       "s": "BIST:THYAO",
 *       "d": ["THYAO", "TURK HAVA YOLLARI", ..., 287.50, ..., 3.45, ...]
 *     }
 *   ]
 * }
 * }</pre>
 *
 * @see com.scyborsa.api.service.screener.TradingViewScreenerClient
 */
@Data
public class TvScreenerResponse {

    /** API'nin dondurdugu toplam sonuc sayisi. */
    private Integer totalCount;

    /** Tarama sonuc verileri. */
    private List<DataItem> data;

    /** Post-parse olarak set edilen tarama stratejisi adi. */
    private String screenerName;

    /**
     * Tek bir tarama sonuc kaydi.
     *
     * <p>{@code s} alani hisse sembolunu ("BIST:THYAO" formatinda),
     * {@code d} array'i ise kolon degerlerini (name, desc, ..., close, change, volume)
     * sirasyla icerir.</p>
     */
    @Data
    public static class DataItem {

        /** Hisse sembolu (or. "BIST:THYAO"). */
        private String s;

        /** Kolon degerleri array'i. Indeks siralamasi scan body'deki columns dizisine baglidir. */
        private List<Object> d;
    }
}
