package com.scyborsa.api.controller;

import com.scyborsa.api.model.chart.BarUpdateMessage;
import com.scyborsa.api.model.chart.CandleBar;
import com.scyborsa.api.service.chart.TradingViewBarService;
import com.scyborsa.api.utils.BistTradingCalendar;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Grafik (chart) verisi sunan REST controller.
 *
 * <p>TradingView bar servisinden mum (candle) verilerini çeker ve istemciye sunar.
 * Ayrıca servis durumu hakkında bilgi veren bir durum endpoint'i içerir.</p>
 *
 * <p>Temel endpoint: {@code /api/v1/chart}</p>
 *
 * @see com.scyborsa.api.service.chart.TradingViewBarService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chart")
@RequiredArgsConstructor
public class ChartController {

    private final TradingViewBarService barService;

    /**
     * Belirtilen sembol için mum (candle bar) verilerini döndürür.
     *
     * <p>HTTP GET {@code /api/v1/chart/{symbol}}</p>
     *
     * @param symbol hisse senedi sembolü (örn. "THYAO")
     * @param period mum periyodu, varsayılan "30" (dakika cinsinden)
     * @param bars   istenilen bar sayısı, varsayılan 300
     * @return {@link BarUpdateMessage} içinde mum verileri;
     *         veri yoksa 204 No Content, hata durumunda 500 Internal Server Error
     */
    @GetMapping("/{symbol}")
    public ResponseEntity<BarUpdateMessage> getBars(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "30") String period,
            @RequestParam(defaultValue = "300") int bars) {

        log.info("[CHART-API] GET /api/v1/chart/{} period={} bars={}", symbol, period, bars);

        try {
            List<CandleBar> result = barService.getBars(symbol, period, bars)
                    .get(15, TimeUnit.SECONDS);

            if (result == null || result.isEmpty()) {
                return ResponseEntity.noContent().build();
            }

            BarUpdateMessage response = BarUpdateMessage.builder()
                    .symbol(symbol)
                    .period(period)
                    .type("initial")
                    .bars(result)
                    .totalBars(result.size())
                    .serverTime(System.currentTimeMillis())
                    .marketOpen(BistTradingCalendar.isMarketOpen())
                    .build();

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[CHART-API] Bar çekme hatası: {} - {}", symbol, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Grafik servisinin güncel durumunu döndürür.
     *
     * <p>HTTP GET {@code /api/v1/chart/status}</p>
     *
     * @return servis durum bilgilerini içeren anahtar-değer haritası
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(barService.getStatus());
    }
}
