package com.scyborsa.api.service.alert;

import com.scyborsa.api.dto.alert.AlertNotificationDto;
import com.scyborsa.api.enums.AlertDirection;
import com.scyborsa.api.enums.AlertStatus;
import com.scyborsa.api.model.PriceAlert;
import com.scyborsa.api.repository.PriceAlertRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Fiyat alarmi motoru — bellek-ici index ve fiyat kontrol mantigi.
 *
 * <p>Aktif alarmlari {@code stockCode → List<PriceAlert>} seklinde bellek-ici
 * indeksler. Her fiyat guncellemesinde ({@link #checkPrice(String, double)})
 * ilgili alarmlari kontrol eder ve kosul saglandiginda tetikler.</p>
 *
 * <p>Tetiklenen alarmlar:</p>
 * <ol>
 *   <li>Veritabaninda {@link AlertStatus#TRIGGERED} olarak guncellenir</li>
 *   <li>Bellek-ici indeksten cikarilir</li>
 *   <li>Kullaniciya WebSocket ({@code /user/{email}/queue/notifications}) uzerinden bildirilir</li>
 * </ol>
 *
 * <p>Thread-safe: {@link ConcurrentHashMap} + {@link CopyOnWriteArrayList} kullanir.</p>
 *
 * @see PriceAlertService
 * @see com.scyborsa.api.service.chart.QuotePriceCache
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceAlertEngine {

    private final PriceAlertRepository alertRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /** Hisse kodu bazinda aktif alarm indeksi (stockCode → alarm listesi). */
    private final ConcurrentHashMap<String, List<PriceAlert>> alertIndex = new ConcurrentHashMap<>();

    /** Ayni anda tetiklenen alarm ID'leri — double-trigger onlemi. */
    private final Set<Long> inFlightTriggers = ConcurrentHashMap.newKeySet();

    /** Ilk yuklemenin tamamlanip tamamlanmadigini belirtir. */
    private volatile boolean initialized = false;

    /**
     * Uygulama baslandiginda aktif alarmlari veritabanindan yukler.
     *
     * <p>Tum {@link AlertStatus#ACTIVE} durumdaki alarmlar hisse koduna
     * gore gruplandirilerek bellek-ici indekse yazilir.</p>
     */
    @PostConstruct
    public void loadActiveAlerts() {
        try {
            List<PriceAlert> activeAlerts = alertRepository.findByStatus(AlertStatus.ACTIVE);
            activeAlerts.stream()
                    .collect(Collectors.groupingBy(a -> a.getStockCode().toUpperCase()))
                    .forEach((stockCode, alerts) ->
                            alertIndex.put(stockCode, new CopyOnWriteArrayList<>(alerts)));

            initialized = true;
            log.info("[ALERT-ENGINE] Aktif alarmlar yuklendi: {} alarm, {} hisse",
                    activeAlerts.size(), alertIndex.size());
        } catch (Exception e) {
            log.error("[ALERT-ENGINE] Aktif alarm yukleme hatasi", e);
            initialized = true; // Hata olsa da motoru baslatarak yeni alarm eklemelerine izin ver
        }
    }

    /**
     * Belirtilen hisse icin fiyat kontrolu yapar.
     *
     * <p>Her fiyat guncellemesinde cagrilir ({@link com.scyborsa.api.service.chart.QuotePriceCache}).
     * Indekste eslesen aktif alarmlar varsa yon ve hedef fiyat kosulunu kontrol eder.</p>
     *
     * @param stockCode    hisse kodu (orn. "THYAO")
     * @param currentPrice mevcut fiyat
     */
    public void checkPrice(String stockCode, double currentPrice) {
        if (!initialized) {
            return;
        }

        List<PriceAlert> alerts = alertIndex.get(stockCode.toUpperCase());
        if (alerts == null || alerts.isEmpty()) {
            return;
        }

        List<PriceAlert> triggered = new ArrayList<>();
        for (PriceAlert alert : alerts) {
            if (alert.getStatus() != AlertStatus.ACTIVE) {
                continue;
            }
            boolean hit = switch (alert.getDirection()) {
                case ABOVE -> currentPrice >= alert.getTargetPrice();
                case BELOW -> currentPrice <= alert.getTargetPrice();
            };
            if (hit) {
                triggered.add(alert);
            }
        }

        for (PriceAlert alert : triggered) {
            triggerAlert(alert, currentPrice);
        }
    }

    /**
     * Yeni olusturulan alarmi bellek-ici indekse ekler.
     *
     * @param alert eklenmek istenen alarm
     */
    public void addToIndex(PriceAlert alert) {
        alertIndex.computeIfAbsent(alert.getStockCode().toUpperCase(),
                        k -> new CopyOnWriteArrayList<>())
                .add(alert);
        log.debug("[ALERT-ENGINE] Alarm indekse eklendi: id={}, stockCode={}", alert.getId(), alert.getStockCode());
    }

    /**
     * Alarmi bellek-ici indeksten cikarir (iptal veya tetiklenme durumunda).
     *
     * @param alert cikarilmak istenen alarm
     */
    public void removeFromIndex(PriceAlert alert) {
        alertIndex.computeIfPresent(alert.getStockCode().toUpperCase(), (k, list) -> {
            list.removeIf(a -> a.getId().equals(alert.getId()));
            return list.isEmpty() ? null : list;
        });
        log.debug("[ALERT-ENGINE] Alarm indeksten cikarildi: id={}, stockCode={}", alert.getId(), alert.getStockCode());
    }

    // ==================== PRIVATE HELPERS ====================

    /**
     * Alarmi tetikler: DB gunceller, indeksten cikarir, WebSocket bildirimi gonderir.
     *
     * @param alert        tetiklenen alarm
     * @param currentPrice tetiklenme anindaki fiyat
     */
    private void triggerAlert(PriceAlert alert, double currentPrice) {
        // Double-trigger onlemi — ayni alarm iki kez tetiklenemez
        if (!inFlightTriggers.add(alert.getId())) {
            return;
        }

        // Indeksten hemen cikar (diger tick'lerde tekrar kontrol edilmesin)
        removeFromIndex(alert);

        // DB + WS islemleri async — WebSocket thread'i bloklama
        try {
        CompletableFuture.runAsync(() -> {
        try {
            // 1. Veritabanini guncelle
            alert.setStatus(AlertStatus.TRIGGERED);
            alert.setTriggerPrice(currentPrice);
            alert.setTriggeredAt(LocalDateTime.now());
            alertRepository.save(alert);

            // 2. Kullaniciya WebSocket bildirimi gonder
            String userEmail = alert.getUserEmail();
            if (userEmail == null || userEmail.isBlank()) {
                log.warn("[ALERT-ENGINE] Alarm icin userEmail eksik: alertId={}", alert.getId());
                return;
            }

            AlertNotificationDto notification = AlertNotificationDto.builder()
                    .alertId(alert.getId())
                    .stockCode(alert.getStockCode())
                    .stockName(alert.getStockName())
                    .direction(alert.getDirection().name())
                    .targetPrice(alert.getTargetPrice())
                    .triggerPrice(currentPrice)
                    .triggeredAt(alert.getTriggeredAt())
                    .message(buildMessage(alert, currentPrice))
                    .build();

            messagingTemplate.convertAndSendToUser(
                    userEmail, "/queue/notifications", notification);

            log.info("[ALERT-ENGINE] Alarm tetiklendi: {} {} {} fiyat={}",
                    alert.getStockCode(), alert.getDirection(), alert.getTargetPrice(), currentPrice);
        } catch (Exception e) {
            log.error("[ALERT-ENGINE] Alarm tetikleme hatasi: alertId={}", alert.getId(), e);
            // DB kaydi basarisiz — tum mutasyonlari geri al, sonraki tick'te tekrar denensin
            alert.setStatus(AlertStatus.ACTIVE);
            alert.setTriggerPrice(null);
            alert.setTriggeredAt(null);
            addToIndex(alert);
        } finally {
            inFlightTriggers.remove(alert.getId());
        }
        }); // CompletableFuture.runAsync end
        } catch (Exception e) {
            // runAsync submission failed — re-add to index
            inFlightTriggers.remove(alert.getId());
            addToIndex(alert);
            log.error("[ALERT-ENGINE] Async submission hatasi: alertId={}", alert.getId(), e);
        }
    }

    /**
     * Tetiklenen alarm icin kullaniciya gosterilecek mesaji olusturur.
     *
     * @param alert        tetiklenen alarm
     * @param currentPrice tetiklenme anindaki fiyat
     * @return Turkce bildirim mesaji
     */
    private String buildMessage(PriceAlert alert, double currentPrice) {
        String dir = alert.getDirection() == AlertDirection.ABOVE ? "ustune cikti" : "altina dustu";
        return String.format("%s %.2f₺'nin %s (%.2f₺)",
                alert.getStockCode(), alert.getTargetPrice(), dir, currentPrice);
    }
}
