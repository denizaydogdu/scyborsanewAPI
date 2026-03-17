package com.scyborsa.api.service.screener;

import com.scyborsa.api.dto.screener.TvScreenerResponse;
import com.scyborsa.api.enums.ScreenerTimesEnum;
import com.scyborsa.api.enums.ScreenerTypeEnum;
import com.scyborsa.api.model.screener.ScreenerResultModel;
import com.scyborsa.api.repository.ScreenerResultRepository;
import com.scyborsa.api.repository.StockModelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tarama sonuçlarını veritabanına kaydeden servis (write-only path).
 *
 * <p>{@link TvScreenerResponse} verilerini {@link ScreenerResultModel}'e dönüştürüp
 * duplicate kontrolü yaparak kaydeder.</p>
 *
 * @see ScreenerResultModel
 * @see ScreenerResultRepository
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScreenerResultPersistService {

    /** Istanbul saat dilimi (Europe/Istanbul). */
    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");

    /**
     * d[] array column indeksleri (13-27 elemanli standart taramalar).
     * TÜM scan body JSON dosyalarindaki columns dizisi bu sirayi korumalidir.
     */
    private static final int IDX_CLOSE_PRICE = 6;
    /** d[] array'de degisim yuzdesi sutun indeksi (standart). */
    private static final int IDX_CHANGE_PERCENT = 12;

    /**
     * d[] array column indeksleri (28+ elemanli taramalar — SC uyumluluk).
     * Bazi taramalar 28+ column dondurur, bu durumda fiyat ve yuzde farkli indekslerde.
     */
    private static final int IDX_CLOSE_PRICE_LARGE = 7;
    /** d[] array'de degisim yuzdesi sutun indeksi (28+ elemanli). */
    private static final int IDX_CHANGE_PERCENT_LARGE = 6;
    /** 28+ eleman esik degeri. */
    private static final int LARGE_DATA_THRESHOLD = 28;

    /** Gecerli bir data item icin gereken minimum sutun sayisi. */
    private static final int MIN_COLUMNS_REQUIRED = 13;

    /** SC uyumluluk: 0.50 TL altindaki fiyatlar supheli — kayit atlanir. */
    private static final double MIN_VALID_PRICE = 0.50;

    /** Tarama sonuclarini saklayan JPA repository. */
    private final ScreenerResultRepository screenerResultRepository;

    /** Hisse master data repository (banned stock kontrolu icin). */
    private final StockModelRepository stockModelRepository;

    /** Banned stock cache — her tarama turunde DB'ye gitmemek icin in-memory. */
    private volatile Set<String> bannedStockCache = ConcurrentHashMap.newKeySet();

    /** Banned stock cache son yenilenme zamani (epoch millis). */
    private volatile long bannedCacheLastRefresh = 0L;

    /** Banned stock cache TTL — BistStockSyncJob 09:25'te gunceller, 30dk yeterli. */
    private static final long BANNED_CACHE_TTL_MS = 30 * 60 * 1000L;

    /**
     * Tarama sonuçlarını veritabanına kaydeder.
     *
     * <p>Her {@link TvScreenerResponse} içindeki data item'ları parse edilerek
     * {@link ScreenerResultModel}'e dönüştürülür. Aynı stock + time + day + screenerName
     * kombinasyonu zaten varsa atlanır (duplicate detection).</p>
     *
     * @param responses API'den dönen tarama sonuçları
     * @param timeSlot tarama zaman dilimi
     * @param screenerType tarama türü
     * @return kaydedilen toplam kayıt sayısı
     */
    @Transactional
    public int saveResults(List<TvScreenerResponse> responses, ScreenerTimesEnum timeSlot,
                           ScreenerTypeEnum screenerType) {
        LocalDate today = LocalDate.now(ISTANBUL_ZONE);
        LocalTime screenerTime = timeSlot.toLocalTime();
        List<ScreenerResultModel> toSave = new ArrayList<>();

        // Banned stock cache'i yenile (her saveResults cagrisinda degil, bossa yenile)
        refreshBannedCacheIfNeeded();

        for (TvScreenerResponse response : responses) {
            if (response == null || response.getData() == null) continue;

            for (TvScreenerResponse.DataItem item : response.getData()) {
                ScreenerResultModel entity = mapToEntity(item, response.getScreenerName(),
                        screenerType, screenerTime, today);
                if (entity == null) continue;

                // SC uyumluluk: banned hisseler atlanir
                if (bannedStockCache.contains(entity.getStockName())) {
                    log.debug("[SCREENER-PERSIST] Banned hisse atlandi: {}", entity.getStockName());
                    continue;
                }

                // Duplicate detection
                boolean exists = screenerResultRepository
                        .existsByStockNameAndScreenerDayAndScreenerTimeAndScreenerName(
                                entity.getStockName(), today, screenerTime, entity.getScreenerName());
                if (!exists) {
                    toSave.add(entity);
                }
            }
        }

        if (!toSave.isEmpty()) {
            int savedCount = saveWithDuplicateProtection(toSave);
            log.info("[SCREENER-PERSIST] {} kayıt kaydedildi (tür={}, zaman={})",
                    savedCount, screenerType.getCode(), timeSlot.getTimeStr());
            return savedCount;
        }

        return 0;
    }

    /**
     * Batch kaydetmeyi dener; UK violation durumunda teker teker fallback yapar.
     *
     * @param toSave kaydedilecek entity'ler
     * @return başarıyla kaydedilen kayıt sayısı
     */
    private int saveWithDuplicateProtection(List<ScreenerResultModel> toSave) {
        try {
            screenerResultRepository.saveAll(toSave);
            return toSave.size();
        } catch (DataIntegrityViolationException e) {
            log.warn("[SCREENER-PERSIST] Batch'te duplicate tespit edildi, teker teker kaydediliyor");
            int savedCount = 0;
            for (ScreenerResultModel entity : toSave) {
                try {
                    screenerResultRepository.save(entity);
                    savedCount++;
                } catch (DataIntegrityViolationException ignored) {
                    // UK violation — duplicate, atla
                }
            }
            return savedCount;
        }
    }

    /**
     * TvScreenerResponse.DataItem'i ScreenerResultModel'e donusturur.
     *
     * <p>d[] array indeksleri veri boyutuna gore degisir (SC uyumluluk):</p>
     * <ul>
     *   <li>28+ eleman: d[7]=price, d[6]=percentage</li>
     *   <li>13-27 eleman: d[6]=price, d[12]=percentage</li>
     *   <li>13'ten az: atlanir</li>
     * </ul>
     *
     * @param item API response data item
     * @param screenerName tarama stratejisi adi
     * @param screenerType tarama turu
     * @param screenerTime tarama zamani
     * @param screenerDay tarama gunu
     * @return donusturulmus entity; parse hatasi durumunda {@code null}
     */
    private ScreenerResultModel mapToEntity(TvScreenerResponse.DataItem item, String screenerName,
                                             ScreenerTypeEnum screenerType, LocalTime screenerTime,
                                             LocalDate screenerDay) {
        try {
            // "BIST:THYAO" → "THYAO"
            String stockName = item.getS();
            if (stockName == null) return null;
            stockName = stockName.replace("BIST:", "");

            List<Object> d = item.getD();
            if (d == null || d.size() < MIN_COLUMNS_REQUIRED) return null;

            // SC uyumluluk: d[] boyutuna gore index belirleme
            int priceIndex;
            int percentageIndex;
            int dataSize = d.size();

            if (dataSize >= LARGE_DATA_THRESHOLD) {
                // 28+ elemanli taramalar
                priceIndex = IDX_CLOSE_PRICE_LARGE;
                percentageIndex = IDX_CHANGE_PERCENT_LARGE;
            } else {
                // 13-27 elemanli standart taramalar
                priceIndex = IDX_CLOSE_PRICE;
                percentageIndex = IDX_CHANGE_PERCENT;
            }

            Double price = parseDouble(d.get(priceIndex));
            Double percentage = parseDouble(d.get(percentageIndex));

            // SC uyumluluk: percentage non-numeric ise 0.0 (TRY, USD gibi)
            if (percentage == null) {
                percentage = 0.0;
            }

            // SC uyumluluk: minimum fiyat kontrolu (1.00 TL bug fix)
            if (price == null || price <= 0) {
                return null;
            }
            if (price < MIN_VALID_PRICE) {
                log.warn("[SCREENER-PERSIST] Supheli fiyat atlandi: {} | {} TL (min: {} TL)",
                        stockName, String.format("%.2f", price), MIN_VALID_PRICE);
                return null;
            }

            // SC uyumluluk: fiyat 2 ondalik basamaga yuvarla
            price = Math.round(price * 100.0) / 100.0;

            var entity = new ScreenerResultModel();
            entity.setStockName(stockName);
            entity.setPrice(price);
            entity.setPercentage(percentage);
            entity.setScreenerTime(screenerTime);
            entity.setScreenerDay(screenerDay);
            entity.setScreenerName(screenerName);
            entity.setScreenerType(screenerType);
            entity.setSubScanName(screenerName);
            return entity;
        } catch (Exception e) {
            log.debug("[SCREENER-PERSIST] Data parse hatasi: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Banned stock cache'i bossa DB'den yukler.
     * Gun icinde bir kez yuklenir (BistStockSyncJob 09:25'te gunceller).
     */
    private void refreshBannedCacheIfNeeded() {
        long now = System.currentTimeMillis();
        if ((now - bannedCacheLastRefresh) < BANNED_CACHE_TTL_MS) {
            return;
        }
        try {
            List<String> bannedNames = stockModelRepository.findBannedStockNames();
            Set<String> fresh = ConcurrentHashMap.newKeySet();
            fresh.addAll(bannedNames);
            bannedStockCache = fresh;
            bannedCacheLastRefresh = now;
            if (!bannedStockCache.isEmpty()) {
                log.info("[SCREENER-PERSIST] Banned stock cache yuklendi: {} hisse", bannedStockCache.size());
            }
        } catch (Exception e) {
            log.warn("[SCREENER-PERSIST] Banned stock cache yuklenemedi: {}", e.getMessage());
        }
    }

    private Double parseDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number num) return num.doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
