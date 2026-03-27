package com.scyborsa.api.service.alert;

import com.scyborsa.api.dto.alert.CreateAlertRequest;
import com.scyborsa.api.dto.alert.PriceAlertDto;
import com.scyborsa.api.enums.AlertDirection;
import com.scyborsa.api.enums.AlertStatus;
import com.scyborsa.api.model.AppUser;
import com.scyborsa.api.model.PriceAlert;
import com.scyborsa.api.repository.AppUserRepository;
import com.scyborsa.api.repository.PriceAlertRepository;
import com.scyborsa.api.service.chart.QuotePriceCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Fiyat alarmi servis sinifi.
 *
 * <p>Kullanicilarin fiyat alarmi olusturma, listeleme, iptal etme ve
 * bildirim yonetimi islemlerini gerceklestirir.</p>
 *
 * <p>{@link QuotePriceCache} opsiyonel olarak enjekte edilir; mevcut degilse
 * anlik fiyat zenginlestirmesi yapilmaz (graceful degradation).</p>
 *
 * @see PriceAlert
 * @see PriceAlertRepository
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceAlertService {

    /** Kullanici basina maksimum aktif alarm sayisi. */
    private static final int MAX_ACTIVE_ALERTS_PER_USER = 20;

    private final PriceAlertRepository alertRepository;
    private final AppUserRepository userRepository;

    /** Anlik fiyat cache — mevcut degilse null (graceful degradation). */
    @Autowired(required = false)
    private QuotePriceCache quotePriceCache;

    /** Fiyat alarm motoru — mevcut degilse null (graceful degradation). */
    @Autowired(required = false)
    private PriceAlertEngine alertEngine;

    /**
     * Yeni bir fiyat alarmi olusturur.
     *
     * <p>Ayni kullanici, hisse, yon ve ACTIVE durumda duplikat alarm kontrolu yapar.
     * Kullanici basina maksimum {@value #MAX_ACTIVE_ALERTS_PER_USER} aktif alarm siniri vardir.</p>
     *
     * @param userId kullanici ID'si
     * @param req    alarm olusturma istegi
     * @return olusturulan alarmin DTO'su
     * @throws RuntimeException stockCode bos, targetPrice gecersiz, duplikat veya limit asildiginda
     */
    @Transactional
    public PriceAlertDto createAlert(Long userId, CreateAlertRequest req) {
        // Validasyon
        if (req.getStockCode() == null || req.getStockCode().isBlank()) {
            throw new RuntimeException("Hisse kodu bos olamaz");
        }
        if (req.getTargetPrice() == null || req.getTargetPrice() <= 0) {
            throw new RuntimeException("Hedef fiyat 0'dan buyuk olmalidir");
        }
        if (req.getDirection() == null || req.getDirection().isBlank()) {
            throw new RuntimeException("Alarm yonu belirtilmelidir");
        }

        AlertDirection direction;
        try {
            direction = AlertDirection.valueOf(req.getDirection().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Gecersiz alarm yonu: " + req.getDirection() + " (ABOVE veya BELOW olmali)");
        }

        // Duplikat kontrol
        boolean exists = alertRepository.existsByUserIdAndStockCodeAndDirectionAndTargetPriceAndStatus(
                userId, req.getStockCode().toUpperCase(), direction, req.getTargetPrice(), AlertStatus.ACTIVE);
        if (exists) {
            throw new RuntimeException("Bu hisse icin ayni yonde aktif bir alarm zaten mevcut");
        }

        // Limit kontrol
        long activeCount = alertRepository.countByUserIdAndStatus(userId, AlertStatus.ACTIVE);
        if (activeCount >= MAX_ACTIVE_ALERTS_PER_USER) {
            throw new RuntimeException("Maksimum " + MAX_ACTIVE_ALERTS_PER_USER + " aktif alarm olusturabilirsiniz");
        }

        // Kullanici bul
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Kullanici bulunamadi: " + userId));

        // Anlik fiyat: cache'ten veya istemciden gelen deger
        Double currentPrice = getCurrentPrice(req.getStockCode().toUpperCase());
        Double priceAtCreation = currentPrice != null ? currentPrice : req.getPriceAtCreation();

        // Entity olustur ve kaydet
        PriceAlert alert = PriceAlert.builder()
                .user(user)
                .userEmail(user.getEmail())
                .stockCode(req.getStockCode().toUpperCase())
                .stockName(req.getStockName())
                .direction(direction)
                .targetPrice(req.getTargetPrice())
                .priceAtCreation(priceAtCreation)
                .note(req.getNote() != null && req.getNote().length() > 200
                        ? req.getNote().substring(0, 200) : req.getNote())
                .build();

        PriceAlert saved = alertRepository.save(alert);
        log.info("Fiyat alarmi olusturuldu: userId={}, stockCode={}, direction={}, targetPrice={}",
                userId, saved.getStockCode(), direction, saved.getTargetPrice());

        // Alarm motoruna bildir
        if (alertEngine != null) {
            alertEngine.addToIndex(saved);
        }

        return toDto(saved);
    }

    /**
     * Kullanicinin alarmlarini getirir, opsiyonel durum filtresi ile.
     *
     * <p>Anlik fiyat ve hedef fiyata uzaklik yuzdesi zenginlestirmesi yapilir
     * (QuotePriceCache mevcut ise).</p>
     *
     * @param userId kullanici ID'si
     * @param status alarm durumu filtresi (null ise tum alarmlar)
     * @return alarm DTO listesi
     */
    @Transactional(readOnly = true)
    public List<PriceAlertDto> getUserAlerts(Long userId, String status) {
        List<PriceAlert> alerts;

        if (status != null && !status.isBlank()) {
            try {
                AlertStatus alertStatus = AlertStatus.valueOf(status.toUpperCase());
                alerts = alertRepository.findByUserIdAndStatusOrderByCreateTimeDesc(userId, alertStatus);
            } catch (IllegalArgumentException e) {
                log.warn("Gecersiz alarm durumu filtresi: {}", status);
                alerts = alertRepository.findByUserIdOrderByCreateTimeDesc(userId);
            }
        } else {
            alerts = alertRepository.findByUserIdOrderByCreateTimeDesc(userId);
        }

        return alerts.stream()
                .map(this::toDto)
                .map(this::enrichWithCurrentPrice)
                .toList();
    }

    /**
     * Belirtilen alarmi iptal eder.
     *
     * @param userId  kullanici ID'si (sahiplik dogrulamasi icin)
     * @param alertId alarm ID'si
     * @throws RuntimeException alarm bulunamazsa veya kullaniciya ait degilse
     */
    @Transactional
    public void cancelAlert(Long userId, Long alertId) {
        PriceAlert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new RuntimeException("Alarm bulunamadi: " + alertId));

        if (!alert.getUser().getId().equals(userId)) {
            throw new RuntimeException("Bu alarm size ait degil");
        }

        if (alert.getStatus() != AlertStatus.ACTIVE) {
            throw new RuntimeException("Sadece aktif alarmlar iptal edilebilir");
        }

        // Önce indeksten çıkar — engine'in tekrar tetiklemesini engelle
        if (alertEngine != null) {
            alertEngine.removeFromIndex(alert);
        }

        alert.setStatus(AlertStatus.CANCELLED);
        alert.setCancelledAt(LocalDateTime.now());
        alertRepository.save(alert);

        log.info("Fiyat alarmi iptal edildi: alertId={}, userId={}", alertId, userId);
    }

    /**
     * Kullanicinin okunmamis tetiklenmis alarm sayisini doner.
     *
     * @param userId kullanici ID'si
     * @return okunmamis alarm sayisi
     */
    @Transactional(readOnly = true)
    public long getUnreadCount(Long userId) {
        return alertRepository.countByUserIdAndStatusAndReadAtIsNull(userId, AlertStatus.TRIGGERED);
    }

    /**
     * Belirtilen alarmi okundu olarak isaretler.
     *
     * @param userId  kullanici ID'si (sahiplik dogrulamasi icin)
     * @param alertId alarm ID'si
     * @throws RuntimeException alarm bulunamazsa veya kullaniciya ait degilse
     */
    @Transactional
    public void markRead(Long userId, Long alertId) {
        PriceAlert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new RuntimeException("Alarm bulunamadi: " + alertId));

        if (!alert.getUser().getId().equals(userId)) {
            throw new RuntimeException("Bu alarm size ait degil");
        }

        alert.setReadAt(LocalDateTime.now());
        alertRepository.save(alert);
    }

    /**
     * Kullanicinin tum okunmamis tetiklenmis alarmlarini toplu olarak okundu isaretler.
     *
     * @param userId kullanici ID'si
     * @return guncellenen kayit sayisi
     */
    @Transactional
    public int markAllRead(Long userId) {
        int updated = alertRepository.markAllRead(userId, LocalDateTime.now(), AlertStatus.TRIGGERED);
        if (updated > 0) {
            log.info("Toplu okundu isaretleme: userId={}, count={}", userId, updated);
        }
        return updated;
    }

    /**
     * Mevcut bir fiyat alarmini gunceller.
     *
     * <p>Sadece aktif alarmlar guncellenebilir. Hisse kodu degistirilemez,
     * yon, hedef fiyat ve not alanlari guncellenebilir. Alarm motoru indeksi
     * eski degerlerle cikarilip yeni degerlerle yeniden eklenir.</p>
     *
     * @param userId  kullanici ID'si (sahiplik dogrulamasi icin)
     * @param alertId alarm ID'si
     * @param req     alarm guncelleme istegi
     * @return guncellenmis alarmin DTO'su
     * @throws RuntimeException alarm bulunamazsa, kullaniciya ait degilse veya aktif degilse
     */
    @Transactional
    public PriceAlertDto updateAlert(Long userId, Long alertId, CreateAlertRequest req) {
        PriceAlert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new RuntimeException("Alarm bulunamadi: " + alertId));

        if (!alert.getUser().getId().equals(userId)) {
            throw new RuntimeException("Bu alarm size ait degil");
        }

        if (alert.getStatus() != AlertStatus.ACTIVE) {
            throw new RuntimeException("Sadece aktif alarmlar duzenlenebilir");
        }

        // Alanlari guncelle
        if (req.getDirection() == null || req.getDirection().isBlank()) {
            throw new RuntimeException("Alarm yonu belirtilmelidir");
        }

        AlertDirection direction;
        try {
            direction = AlertDirection.valueOf(req.getDirection().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Gecersiz alarm yonu: " + req.getDirection() + " (ABOVE veya BELOW olmali)");
        }

        if (req.getTargetPrice() == null || req.getTargetPrice() <= 0) {
            throw new RuntimeException("Hedef fiyat 0'dan buyuk olmalidir");
        }

        alert.setDirection(direction);
        alert.setTargetPrice(req.getTargetPrice());
        alert.setNote(req.getNote() != null && req.getNote().length() > 200
                ? req.getNote().substring(0, 200) : req.getNote());
        // stockCode degistirilemez

        // Kaydet, sonra indeks guncelle (save-first pattern)
        PriceAlert saved = alertRepository.save(alert);

        if (alertEngine != null) {
            alertEngine.removeFromIndex(alert);
            alertEngine.addToIndex(saved);
        }

        log.info("Fiyat alarmi guncellendi: alertId={}, userId={}, direction={}, targetPrice={}",
                alertId, userId, direction, saved.getTargetPrice());

        return toDto(saved);
    }

    /**
     * Tum kullanicilarin alarmlarini getirir, opsiyonel durum filtresi ile (admin panel icin).
     *
     * @param status alarm durumu filtresi (null ise tum alarmlar)
     * @return alarm DTO listesi
     */
    @Transactional(readOnly = true)
    public List<PriceAlertDto> getAllAlerts(String status) {
        List<PriceAlert> alerts;

        if (status != null && !status.isBlank()) {
            try {
                AlertStatus alertStatus = AlertStatus.valueOf(status.toUpperCase());
                alerts = alertRepository.findByStatusOrderByCreateTimeDesc(alertStatus);
            } catch (IllegalArgumentException e) {
                log.warn("Gecersiz alarm durumu filtresi: {}", status);
                alerts = alertRepository.findAllByOrderByCreateTimeDesc();
            }
        } else {
            alerts = alertRepository.findAllByOrderByCreateTimeDesc();
        }

        return alerts.stream()
                .map(this::toDto)
                .toList();
    }

    // ==================== PRIVATE HELPERS ====================

    /**
     * PriceAlert entity'sini PriceAlertDto'ya donusturur.
     *
     * @param alert entity
     * @return DTO
     */
    private PriceAlertDto toDto(PriceAlert alert) {
        return PriceAlertDto.builder()
                .id(alert.getId())
                .stockCode(alert.getStockCode())
                .stockName(alert.getStockName())
                .direction(alert.getDirection() != null ? alert.getDirection().name() : null)
                .targetPrice(alert.getTargetPrice())
                .priceAtCreation(alert.getPriceAtCreation())
                .status(alert.getStatus() != null ? alert.getStatus().name() : null)
                .triggerPrice(alert.getTriggerPrice())
                .triggeredAt(alert.getTriggeredAt())
                .cancelledAt(alert.getCancelledAt())
                .readAt(alert.getReadAt())
                .note(alert.getNote())
                .createTime(alert.getCreateTime())
                .userEmail(alert.getUserEmail())
                .build();
    }

    /**
     * DTO'yu anlik fiyat ve uzaklik yuzdesi ile zenginlestirir.
     *
     * @param dto zenginlestirilecek DTO
     * @return zenginlestirilmis DTO
     */
    private PriceAlertDto enrichWithCurrentPrice(PriceAlertDto dto) {
        Double currentPrice = getCurrentPrice(dto.getStockCode());
        if (currentPrice != null) {
            dto.setCurrentPrice(currentPrice);
            if (dto.getTargetPrice() != null && currentPrice > 0) {
                double distance = ((dto.getTargetPrice() - currentPrice) / currentPrice) * 100;
                dto.setDistancePercent(Math.round(distance * 100.0) / 100.0);
            }
        }
        return dto;
    }

    /**
     * QuotePriceCache'ten anlik fiyat bilgisini ceker.
     *
     * @param stockCode hisse kodu
     * @return anlik fiyat veya {@code null} (cache mevcut degilse veya veri yoksa)
     */
    private Double getCurrentPrice(String stockCode) {
        if (quotePriceCache == null) {
            return null;
        }
        try {
            Map<String, Object> quoteData = quotePriceCache.get("BIST:" + stockCode);
            if (quoteData != null && quoteData.containsKey("lp")) {
                Object lp = quoteData.get("lp");
                if (lp instanceof Number) {
                    return ((Number) lp).doubleValue();
                }
            }
        } catch (Exception e) {
            log.debug("Fiyat bilgisi alinamadi: stockCode={}, hata={}", stockCode, e.getMessage());
        }
        return null;
    }
}
