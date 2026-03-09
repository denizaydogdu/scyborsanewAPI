package com.scyborsa.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Borsa endeks performans verisini tasiyan DTO.
 *
 * <p>Her endeks icin gunluk, haftalik, aylik, ceyreklik, 6 aylik ve yillik
 * performans degisim yuzdeleri ile son fiyat bilgisini icerir.</p>
 *
 * <p>Veri kaynagi: Velzon API {@code /api/pinescreener/velzon_indexes_performance} endpoint'i.</p>
 *
 * @see com.scyborsa.api.service.IndexPerformanceService
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class IndexPerformanceDto {

    /** Endeks sembol kodu (orn. "XU100", "XBANK"). */
    private String symbol;

    /** Son islem fiyati (kapanis). */
    private double lastPrice;

    /** Gunluk degisim yuzdesi. */
    private double dailyChange;

    /** Haftalik degisim yuzdesi (Perf.W). */
    private double weeklyChange;

    /** Aylik degisim yuzdesi (Perf.1M). */
    private double monthlyChange;

    /** Ceyreklik degisim yuzdesi (Perf.3M). */
    private double quarterlyChange;

    /** 6 aylik degisim yuzdesi (Perf.6M). */
    private double sixMonthChange;

    /** Yilbasından bu yana degisim yuzdesi (Perf.YTD). */
    private double yearlyChange;
}
