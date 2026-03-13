package com.scyborsa.api.service.market;

import com.scyborsa.api.dto.market.MarketMoverDto;
import com.scyborsa.api.dto.market.MarketMoversResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Piyasa hareketlileri (market movers) verilerini bellekte tutan cache servisi.
 *
 * <p>Üc kategori veri saklar:</p>
 * <ul>
 *   <li>Rising (yükselen) - En cok yükselen hisseler</li>
 *   <li>Falling (düsen) - En cok düsen hisseler</li>
 *   <li>Volume (hacim) - En yüksek hacimli hisseler</li>
 * </ul>
 *
 * <p>Tüm listeler {@code volatile} olarak tutulur ve immutable kopyalar
 * üzerinden güncellenir (thread-safe).</p>
 *
 * @see com.scyborsa.api.service.job.MarketMoversJob
 * @see MarketMoversBroadcastService
 */
@Service
public class MarketMoversCache {

    /** En cok yukselen hisse listesi. */
    private volatile List<MarketMoverDto> rising = List.of();

    /** En cok dusen hisse listesi. */
    private volatile List<MarketMoverDto> falling = List.of();

    /** En yuksek hacimli hisse listesi. */
    private volatile List<MarketMoverDto> volume = List.of();

    /** Son yukselen guncelleme zamani (epoch millis). */
    private volatile long lastRisingUpdate = 0;

    /** Son dusen guncelleme zamani (epoch millis). */
    private volatile long lastFallingUpdate = 0;

    /** Son hacim guncelleme zamani (epoch millis). */
    private volatile long lastVolumeUpdate = 0;

    /**
     * Yükselen hisse listesini günceller.
     *
     * @param stocks yeni yükselen hisse listesi (immutable kopyasi alinir)
     */
    public void updateRising(List<MarketMoverDto> stocks) {
        this.rising = List.copyOf(stocks);
        this.lastRisingUpdate = System.currentTimeMillis();
    }

    /**
     * Düsen hisse listesini günceller.
     *
     * @param stocks yeni düsen hisse listesi (immutable kopyasi alinir)
     */
    public void updateFalling(List<MarketMoverDto> stocks) {
        this.falling = List.copyOf(stocks);
        this.lastFallingUpdate = System.currentTimeMillis();
    }

    /**
     * Yüksek hacimli hisse listesini günceller.
     *
     * @param stocks yeni yüksek hacimli hisse listesi (immutable kopyasi alinir)
     */
    public void updateVolume(List<MarketMoverDto> stocks) {
        this.volume = List.copyOf(stocks);
        this.lastVolumeUpdate = System.currentTimeMillis();
    }

    /**
     * Cache'teki yükselen hisse listesini döner.
     *
     * @return yükselen hisse listesi; henüz veri yoksa bos liste
     */
    public List<MarketMoverDto> getRising() {
        return rising;
    }

    /**
     * Cache'teki düsen hisse listesini döner.
     *
     * @return düsen hisse listesi; henüz veri yoksa bos liste
     */
    public List<MarketMoverDto> getFalling() {
        return falling;
    }

    /**
     * Cache'teki yüksek hacimli hisse listesini döner.
     *
     * @return yüksek hacimli hisse listesi; henüz veri yoksa bos liste
     */
    public List<MarketMoverDto> getVolume() {
        return volume;
    }

    /**
     * Rising ve falling verilerinin anlik görüntüsünü (snapshot) döner.
     *
     * <p>Timestamp olarak rising ve falling güncellemelerinden en büyük olani kullanilir.</p>
     *
     * @return rising, falling listeleri ve timestamp iceren response nesnesi
     */
    public MarketMoversResponse getSnapshot() {
        long timestamp = Math.max(lastRisingUpdate, lastFallingUpdate);
        return new MarketMoversResponse(rising, falling, timestamp);
    }

    /**
     * Rising veya falling verisi olup olmadigini kontrol eder.
     *
     * @return en az bir güncelleme yapildiysa {@code true}
     */
    public boolean hasData() {
        return lastRisingUpdate > 0 || lastFallingUpdate > 0;
    }

    /**
     * Hacim (volume) verisi olup olmadigini kontrol eder.
     *
     * @return en az bir hacim güncellemesi yapildiysa {@code true}
     */
    public boolean hasVolumeData() {
        return lastVolumeUpdate > 0;
    }
}
