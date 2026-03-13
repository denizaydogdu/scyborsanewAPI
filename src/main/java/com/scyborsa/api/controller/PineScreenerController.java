package com.scyborsa.api.controller;

import com.scyborsa.api.dto.screener.TvScreenerResponseModel;
import com.scyborsa.api.service.market.PineScreenerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * TradingView Pine Screener verilerini sunan REST controller.
 *
 * <p>Belirli bir hisse senedi ve indikatör kombinasyonu için Pine Screener
 * sonuçlarını döndürür.</p>
 *
 * <p>Temel endpoint: {@code /api/v1/tw}</p>
 *
 * @see com.scyborsa.api.service.market.PineScreenerService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tw")
@RequiredArgsConstructor
public class PineScreenerController {

    private final PineScreenerService pineScreenerService;

    /**
     * Belirtilen hisse senedi ve indikatör için Pine Screener verilerini döndürür.
     *
     * <p>HTTP GET {@code /api/v1/tw/pineScreenerData/{stockId}/{indicatorName}}</p>
     *
     * @param stockId       hisse senedi kimliği (örn. "THYAO")
     * @param indicatorName TradingView indikatör adı (örn. "RSI", "MACD")
     * @return ilgili indikatör sonuçlarını içeren {@link TvScreenerResponseModel} listesi
     */
    @GetMapping("/pineScreenerData/{stockId}/{indicatorName}")
    public ResponseEntity<List<TvScreenerResponseModel>> getPineScreenerData(
            @PathVariable String stockId,
            @PathVariable String indicatorName) {
        log.info("Pine Screener veri istegi: stockId={}, indicator={}", stockId, indicatorName);
        var result = pineScreenerService.getPineScreenerDataForStock(stockId, indicatorName);
        return ResponseEntity.ok(result);
    }
}
