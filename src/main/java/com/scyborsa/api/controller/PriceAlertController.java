package com.scyborsa.api.controller;

import com.scyborsa.api.dto.alert.CreateAlertRequest;
import com.scyborsa.api.dto.alert.PriceAlertDto;
import com.scyborsa.api.service.alert.PriceAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Fiyat alarmi REST controller'i.
 *
 * <p>Kullanicilarin fiyat alarmi olusturma, listeleme, iptal etme ve
 * bildirim yonetimi endpoint'lerini saglar.</p>
 *
 * <p><b>Base path:</b> {@code /api/v1/alerts}</p>
 *
 * <p><b>Not:</b> userId simdilik request parametresi olarak alinir.
 * Ileride security context'ten cikarilacaktir.</p>
 *
 * @see PriceAlertService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/alerts")
@RequiredArgsConstructor
public class PriceAlertController {

    private final PriceAlertService priceAlertService;

    /**
     * Yeni bir fiyat alarmi olusturur.
     *
     * @param userId kullanici ID'si
     * @param req    alarm olusturma istegi
     * @return olusturulan alarm DTO'su
     */
    @PostMapping
    public ResponseEntity<PriceAlertDto> createAlert(@RequestParam Long userId,
                                                     @Valid @RequestBody CreateAlertRequest req) {
        PriceAlertDto created = priceAlertService.createAlert(userId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Kullanicinin alarmlarini listeler.
     *
     * @param userId kullanici ID'si
     * @param status opsiyonel durum filtresi (ACTIVE, TRIGGERED, CANCELLED, EXPIRED)
     * @return alarm DTO listesi
     */
    @GetMapping
    public ResponseEntity<List<PriceAlertDto>> getUserAlerts(@RequestParam Long userId,
                                                             @RequestParam(required = false) String status) {
        List<PriceAlertDto> alerts = priceAlertService.getUserAlerts(userId, status);
        return ResponseEntity.ok(alerts);
    }

    /**
     * Kullanicinin okunmamis tetiklenmis alarm sayisini doner.
     *
     * @param userId kullanici ID'si
     * @return okunmamis alarm sayisi
     */
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> getUnreadCount(@RequestParam Long userId) {
        long count = priceAlertService.getUnreadCount(userId);
        return ResponseEntity.ok(Map.of("unreadCount", count));
    }

    /**
     * Belirtilen alarmi iptal eder.
     *
     * @param userId kullanici ID'si
     * @param id     alarm ID'si
     * @return 200 OK
     */
    @PutMapping("/{id}/cancel")
    public ResponseEntity<Void> cancelAlert(@RequestParam Long userId, @PathVariable Long id) {
        priceAlertService.cancelAlert(userId, id);
        return ResponseEntity.ok().build();
    }

    /**
     * Belirtilen alarmi okundu olarak isaretler.
     *
     * @param userId kullanici ID'si
     * @param id     alarm ID'si
     * @return 200 OK
     */
    @PutMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@RequestParam Long userId, @PathVariable Long id) {
        priceAlertService.markRead(userId, id);
        return ResponseEntity.ok().build();
    }

    /**
     * Kullanicinin tum okunmamis tetiklenmis alarmlarini toplu olarak okundu isaretler.
     *
     * @param userId kullanici ID'si
     * @return guncellenen kayit sayisi
     */
    @PutMapping("/read-all")
    public ResponseEntity<Map<String, Integer>> markAllRead(@RequestParam Long userId) {
        int updated = priceAlertService.markAllRead(userId);
        return ResponseEntity.ok(Map.of("updatedCount", updated));
    }

    /**
     * RuntimeException'lari 400 Bad Request olarak doner.
     *
     * @param ex hata
     * @return hata mesaji
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntimeException(RuntimeException ex) {
        log.warn("Alarm islemi hatasi: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
