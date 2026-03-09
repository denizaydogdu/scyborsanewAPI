package com.scyborsa.api.dto;

import lombok.Data;

import java.util.List;

/**
 * TradingView Scanner API response DTO.
 *
 * <p>POST {@code scanner.tradingview.com/turkey/scan} endpoint'inin
 * JSON response'unu parse etmek için kullanılır.</p>
 *
 * <h3>Örnek Response Yapısı:</h3>
 * <pre>{@code
 * {
 *   "totalCount": 5,
 *   "data": [
 *     {
 *       "s": "BIST:THYAO",
 *       "d": ["THYAO", "TÜRK HAVA YOLLARI", ..., 287.50, ..., 3.45, ...]
 *     }
 *   ]
 * }
 * }</pre>
 *
 * @see com.scyborsa.api.service.screener.TradingViewScreenerClient
 */
@Data
public class TvScreenerResponse {

    /** API'nin döndürdüğü toplam sonuç sayısı. */
    private Integer totalCount;

    /** Tarama sonuç verileri. */
    private List<DataItem> data;

    /** Post-parse olarak set edilen tarama stratejisi adı. */
    private String screenerName;

    /**
     * Tek bir tarama sonuç kaydı.
     *
     * <p>{@code s} alanı hisse sembolünü ("BIST:THYAO" formatında),
     * {@code d} array'i ise kolon değerlerini (name, desc, ..., close, change, volume)
     * sırasıyla içerir.</p>
     */
    @Data
    public static class DataItem {

        /** Hisse sembolü (ör. "BIST:THYAO"). */
        private String s;

        /** Kolon değerleri array'i. İndeks sıralaması scan body'deki columns dizisine bağlıdır. */
        private List<Object> d;
    }
}
