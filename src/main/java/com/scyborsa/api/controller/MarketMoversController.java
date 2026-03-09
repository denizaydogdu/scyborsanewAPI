package com.scyborsa.api.controller;

import com.scyborsa.api.dto.MarketMoverDto;
import com.scyborsa.api.dto.MarketMoversResponse;
import com.scyborsa.api.service.MarketMoversCache;
import com.scyborsa.api.service.ScreenerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Piyasa hareketlilerini (en çok yükselen/düşen hisseler) sunan REST controller.
 *
 * <p>Önce cache'den veri sunmayı dener; cache boşsa TradingView screener API'sinden
 * güncel veri çeker ve cache'i günceller.</p>
 *
 * <p>Temel endpoint: {@code /api/v1/market}</p>
 *
 * @see com.scyborsa.api.service.MarketMoversCache
 * @see com.scyborsa.api.service.ScreenerService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/market")
@RequiredArgsConstructor
public class MarketMoversController {

    private final MarketMoversCache cache;
    private final ScreenerService screenerService;

    /**
     * En çok yükselen ve en çok düşen hisseleri döndürür.
     *
     * <p>HTTP GET {@code /api/v1/market/movers}</p>
     *
     * <p>Cache'de veri varsa doğrudan cache'den döner.
     * Cache boşsa screener servisinden yükselen ve düşen verileri çekip cache'i doldurur.</p>
     *
     * @return yükselen ve düşen hisse listelerini içeren {@link MarketMoversResponse}
     */
    @GetMapping("/movers")
    public ResponseEntity<MarketMoversResponse> getMarketMovers() {
        if (cache.hasData()) {
            return ResponseEntity.ok(cache.getSnapshot());
        }

        // Fallback: cache boşsa doğrudan API'ye istek at
        log.info("Cache boş, doğrudan screener'dan çekiliyor...");
        List<MarketMoverDto> rising = screenerService.scanRising();
        List<MarketMoverDto> falling = screenerService.scanFalling();

        if (!rising.isEmpty()) cache.updateRising(rising);
        if (!falling.isEmpty()) cache.updateFalling(falling);

        return ResponseEntity.ok(cache.getSnapshot());
    }
}
