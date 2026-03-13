package com.scyborsa.api.service.screener;

import com.scyborsa.api.dto.screener.TvScreenerResponse;
import com.scyborsa.api.enums.ScreenerTimesEnum;
import com.scyborsa.api.enums.ScreenerTypeEnum;
import com.scyborsa.api.model.ScreenerResultModel;
import com.scyborsa.api.repository.ScreenerResultRepository;
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

    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");

    /**
     * d[] array column indeksleri.
     * TÜM scan body JSON dosyalarındaki columns dizisi bu sırayı korumalıdır.
     */
    private static final int IDX_CLOSE_PRICE = 6;
    private static final int IDX_CHANGE_PERCENT = 12;
    private static final int MIN_COLUMNS_REQUIRED = 13;

    private final ScreenerResultRepository screenerResultRepository;

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

        for (TvScreenerResponse response : responses) {
            if (response == null || response.getData() == null) continue;

            for (TvScreenerResponse.DataItem item : response.getData()) {
                ScreenerResultModel entity = mapToEntity(item, response.getScreenerName(),
                        screenerType, screenerTime, today);
                if (entity == null) continue;

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
     * TvScreenerResponse.DataItem'ı ScreenerResultModel'e dönüştürür.
     *
     * <p>d[] array indeksleri:
     * d[0] = name (stockName), d[{@value IDX_CLOSE_PRICE}] = close (price),
     * d[{@value IDX_CHANGE_PERCENT}] = change (percentage)</p>
     *
     * @param item API response data item
     * @param screenerName tarama stratejisi adı
     * @param screenerType tarama türü
     * @param screenerTime tarama zamanı
     * @param screenerDay tarama günü
     * @return dönüştürülmüş entity; parse hatası durumunda {@code null}
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

            Double price = parseDouble(d.get(IDX_CLOSE_PRICE));
            Double percentage = parseDouble(d.get(IDX_CHANGE_PERCENT));

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
            log.debug("[SCREENER-PERSIST] Data parse hatası: {}", e.getMessage());
            return null;
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
