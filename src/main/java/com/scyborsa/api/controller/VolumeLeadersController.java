package com.scyborsa.api.controller;

import com.scyborsa.api.dto.market.MarketMoverDto;
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
 * Hacim liderlerini (en yüksek işlem hacmine sahip hisseler) sunan REST controller.
 *
 * <p>Önce cache'den veri sunmayı dener; cache boşsa TradingView screener API'sinden
 * güncel hacim verisi çeker ve cache'i günceller.</p>
 *
 * <p>Temel endpoint: {@code /api/v1/market/volume}</p>
 *
 * @see com.scyborsa.api.service.MarketMoversCache
 * @see com.scyborsa.api.service.ScreenerService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/market")
@RequiredArgsConstructor
public class VolumeLeadersController {

    private final MarketMoversCache cache;
    private final ScreenerService screenerService;

    /**
     * En yüksek işlem hacmine sahip hisseleri döndürür.
     *
     * <p>HTTP GET {@code /api/v1/market/volume}</p>
     *
     * <p>Cache'de veri varsa doğrudan cache'den döner.
     * Cache boşsa screener servisinden hacim verilerini çekip cache'i doldurur.</p>
     *
     * @return hacim liderlerinin listesi ({@link MarketMoverDto})
     */
    @GetMapping("/volume")
    public ResponseEntity<List<MarketMoverDto>> getVolumeLeaders() {
        if (cache.hasVolumeData()) {
            return ResponseEntity.ok(cache.getVolume());
        }

        log.info("Volume cache boş, doğrudan screener'dan çekiliyor...");
        List<MarketMoverDto> volume = screenerService.scanVolume();
        if (!volume.isEmpty()) {
            cache.updateVolume(volume);
        }
        return ResponseEntity.ok(cache.getVolume());
    }
}
