package com.scyborsa.api.service;

import com.scyborsa.api.dto.MarketMoverDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Piyasa hareketlileri (market movers) verilerini periyodik olarak ceken scheduled job.
 *
 * <p>Her 3 saniyede bir round-robin sirasiyla rising, falling ve volume verilerini cekerrek
 * cache ve WebSocket broadcast islemlerini tetikler. Böylece her kategori 9 saniyede bir güncellenir.</p>
 *
 * <p>Bagimliliklar:</p>
 * <ul>
 *   <li>{@link ScreenerService} - TradingView Screener API'den veri cekme</li>
 *   <li>{@link MarketMoversCache} - Bellekteki cache güncelleme</li>
 *   <li>{@link MarketMoversBroadcastService} - WebSocket üzerinden yayinlama</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketMoversJob {

    private final ScreenerService screenerService;
    private final MarketMoversCache cache;
    private final MarketMoversBroadcastService broadcastService;
    private int fetchState = 0; // 0=rising, 1=falling, 2=volume

    /**
     * Piyasa hareketlileri verilerini round-robin sirasiyla ceker.
     *
     * <p>Her cagrisinda farkli bir kategoriyi isler:
     * 0 = rising (yükselen), 1 = falling (düsen), 2 = volume (hacim).
     * Sonuc bos degilse cache güncellenir ve WebSocket'e yayinlanir.</p>
     *
     * <p>Hatalar loglanir, exception yukariya firlatilmaz.</p>
     */
    @Scheduled(fixedDelay = 3000)
    public void fetchMarketMovers() {
        try {
            switch (fetchState) {
                case 0 -> {
                    List<MarketMoverDto> rising = screenerService.scanRising();
                    if (!rising.isEmpty()) {
                        cache.updateRising(rising);
                        broadcastService.broadcastRising(rising);
                        log.debug("Rising güncellendi: {} hisse", rising.size());
                    }
                }
                case 1 -> {
                    List<MarketMoverDto> falling = screenerService.scanFalling();
                    if (!falling.isEmpty()) {
                        cache.updateFalling(falling);
                        broadcastService.broadcastFalling(falling);
                        log.debug("Falling güncellendi: {} hisse", falling.size());
                    }
                }
                case 2 -> {
                    List<MarketMoverDto> volume = screenerService.scanVolume();
                    if (!volume.isEmpty()) {
                        cache.updateVolume(volume);
                        broadcastService.broadcastVolume(volume);
                        log.debug("Volume güncellendi: {} hisse", volume.size());
                    }
                }
            }
            fetchState = (fetchState + 1) % 3;
        } catch (Exception e) {
            log.error("MarketMovers fetch hatası", e);
        }
    }
}
