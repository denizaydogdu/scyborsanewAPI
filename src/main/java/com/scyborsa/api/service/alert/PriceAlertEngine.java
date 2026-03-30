package com.scyborsa.api.service.alert;

import com.scyborsa.api.dto.alert.AlertNotificationDto;
import com.scyborsa.api.enums.AlertDirection;
import com.scyborsa.api.enums.AlertStatus;
import com.scyborsa.api.model.PriceAlert;
import com.scyborsa.api.repository.PriceAlertRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
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

    /** TradingView WebSocket servisi — alarmlı hisseler için canlı fiyat aboneliği. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.scyborsa.api.service.chart.TradingViewBarService tradingViewBarService;

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
     * Uygulama tamamen baslatildiktan sonra aktif alarmlari WS'e subscribe eder.
     *
     * <p>{@code ApplicationReadyEvent} tum bean'ler ve embedded server hazir olduktan
     * sonra atesler. {@code @Async} ile ayri thread'de calisir — main thread'i bloklamaz.
     * FundService.warmUpCache() ile ayni pattern.</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void subscribeOnStartup() {
        log.info("[ALERT-ENGINE] Startup WS subscribe basladi (ApplicationReadyEvent)");
        try {
            subscribeAllActiveStocks();
        } catch (Exception e) {
            log.warn("[ALERT-ENGINE] Startup WS subscribe hatasi: {}", e.getMessage());
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
        String stockCode = alert.getStockCode().toUpperCase();
        boolean[] isNew = {false};
        alertIndex.compute(stockCode, (k, existing) -> {
            if (existing == null) {
                isNew[0] = true;
                existing = new CopyOnWriteArrayList<>();
            }
            existing.add(alert);
            return existing;
        });
        // WS subscribe lock dışında — blocking I/O ConcurrentHashMap lock altında yapılmaz
        if (isNew[0]) {
            subscribeToQuote(stockCode);
        }
        log.debug("[ALERT-ENGINE] Alarm indekse eklendi: id={}, stockCode={}", alert.getId(), stockCode);
    }

    /**
     * Alarmi bellek-ici indeksten cikarir (iptal veya tetiklenme durumunda).
     *
     * @param alert cikarilmak istenen alarm
     */
    public void removeFromIndex(PriceAlert alert) {
        String stockCode = alert.getStockCode().toUpperCase();
        alertIndex.computeIfPresent(stockCode, (k, list) -> {
            list.removeIf(a -> a.getId().equals(alert.getId()));
            if (list.isEmpty()) {
                log.info("[ALERT-ENGINE] {} icin tum alarmlar kaldirildi — WS subscription aktif kalir (fallback)", stockCode);
                return null;
            }
            return list;
        });
        log.debug("[ALERT-ENGINE] Alarm indeksten cikarildi: id={}, stockCode={}", alert.getId(), stockCode);
    }

    /**
     * Belirtilen hisse kodunu TradingView WebSocket'e canli fiyat aboneligi olarak ekler.
     *
     * @param stockCode hisse kodu (orn. "THYAO")
     */
    private void subscribeToQuote(String stockCode) {
        if (tradingViewBarService != null) {
            try {
                tradingViewBarService.subscribeQuote("BIST:" + stockCode);
                log.info("[ALERT-ENGINE] WS quote subscribe: {}", stockCode);
            } catch (Exception e) {
                log.warn("[ALERT-ENGINE] WS quote subscribe basarisiz: {} — batch scan fallback", stockCode, e);
            }
        }
    }

    /**
     * Tum aktif alarmi olan hisseleri TradingView WebSocket'e subscribe eder.
     * Uygulama baslangicinda cagrilir.
     */
    private void subscribeAllActiveStocks() {
        if (tradingViewBarService == null || alertIndex.isEmpty()) return;
        try {
            for (String stockCode : alertIndex.keySet()) {
                subscribeToQuote(stockCode);
            }
            log.info("[ALERT-ENGINE] {} hisse WS quote subscribe edildi", alertIndex.size());
        } catch (Exception e) {
            log.warn("[ALERT-ENGINE] Toplu WS subscribe basarisiz — batch scan fallback", e);
        }
    }

    /**
     * Aktif alarmi olan hisse kodlarini doner (batch scan job icin).
     *
     * @return aktif alarm olan hisse kodlari seti
     */
    public Set<String> getActiveStockCodes() {
        return Set.copyOf(alertIndex.keySet());
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
        boolean dbSaved = false;
        boolean removedInCatch = false;
        try {
            // 1. Veritabanini guncelle
            alert.setStatus(AlertStatus.TRIGGERED);
            alert.setTriggerPrice(currentPrice);
            alert.setTriggeredAt(LocalDateTime.now());
            alertRepository.save(alert);
            dbSaved = true;

            // 2. Kullaniciya WebSocket bildirimi gonder
            String userEmail = alert.getUserEmail();
            if (userEmail != null && !userEmail.isBlank()) {
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
            }

            log.info("[ALERT-ENGINE] Alarm tetiklendi: {} {} {} fiyat={}",
                    alert.getStockCode(), alert.getDirection(), alert.getTargetPrice(), currentPrice);
        } catch (Exception e) {
            if (!dbSaved) {
                // DB kaydi basarisiz — tum mutasyonlari geri al, sonraki tick'te tekrar denensin
                log.error("[ALERT-ENGINE] Alarm DB kaydi basarisiz, tekrar denenecek: alertId={}", alert.getId(), e);
                alert.setStatus(AlertStatus.ACTIVE);
                alert.setTriggerPrice(null);
                alert.setTriggeredAt(null);
                inFlightTriggers.remove(alert.getId());
                removedInCatch = true;
                addToIndex(alert);
            } else {
                // DB OK ama WS gonderilemedi — alarm tetiklendi, bildirim sonra gonderilecek
                log.error("[ALERT-ENGINE] WS bildirim gonderilemedi, DB kaydi OK: alertId={}", alert.getId(), e);
            }
        } finally {
            if (!removedInCatch) {
                inFlightTriggers.remove(alert.getId());
            }
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
        String dir = alert.getDirection() == AlertDirection.ABOVE ? "hedefe ulaştı ≥" : "hedefe ulaştı ≤";
        return String.format("%s %.2f₺ (%.2f₺)", dir, alert.getTargetPrice(), currentPrice);
    }
}
