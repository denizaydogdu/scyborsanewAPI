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
    private final com.scyborsa.api.repository.AppUserRepository appUserRepository;

    /**
     * Yeni bir fiyat alarmi olusturur.
     *
     * @param email kullanici email adresi
     * @param req   alarm olusturma istegi
     * @return olusturulan alarm DTO'su
     */
    @PostMapping
    public ResponseEntity<PriceAlertDto> createAlert(@RequestParam String email,
                                                     @Valid @RequestBody CreateAlertRequest req) {
        PriceAlertDto created = priceAlertService.createAlert(resolveUserId(email), req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Kullanicinin alarmlarini listeler.
     *
     * @param email  kullanici email adresi
     * @param status opsiyonel durum filtresi (ACTIVE, TRIGGERED, CANCELLED, EXPIRED)
     * @return alarm DTO listesi
     */
    @GetMapping
    public ResponseEntity<List<PriceAlertDto>> getUserAlerts(@RequestParam String email,
                                                             @RequestParam(required = false) String status) {
        List<PriceAlertDto> alerts = priceAlertService.getUserAlerts(resolveUserId(email), status);
        return ResponseEntity.ok(alerts);
    }

    /**
     * Kullanicinin okunmamis tetiklenmis alarm sayisini doner.
     *
     * @param email kullanici email adresi
     * @return okunmamis alarm sayisi
     */
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> getUnreadCount(@RequestParam String email) {
        long count = priceAlertService.getUnreadCount(resolveUserId(email));
        return ResponseEntity.ok(Map.of("count", count));
    }

    /**
     * Mevcut bir fiyat alarmini gunceller.
     *
     * <p>Sadece aktif alarmlar guncellenebilir. Hisse kodu degistirilemez,
     * yon, hedef fiyat ve not alanlari guncellenebilir.</p>
     *
     * @param email kullanici email adresi
     * @param id    alarm ID'si
     * @param req   alarm guncelleme istegi
     * @return guncellenmis alarm DTO'su
     */
    @PutMapping("/{id}")
    public ResponseEntity<PriceAlertDto> updateAlert(@RequestParam String email,
                                                     @PathVariable Long id,
                                                     @Valid @RequestBody CreateAlertRequest req) {
        PriceAlertDto updated = priceAlertService.updateAlert(resolveUserId(email), id, req);
        return ResponseEntity.ok(updated);
    }

    /**
     * Belirtilen alarmi iptal eder.
     *
     * @param email kullanici email adresi
     * @param id    alarm ID'si
     * @return 200 OK
     */
    @PutMapping("/{id}/cancel")
    public ResponseEntity<Void> cancelAlert(@RequestParam String email, @PathVariable Long id) {
        priceAlertService.cancelAlert(resolveUserId(email), id);
        return ResponseEntity.ok().build();
    }

    /**
     * Belirtilen alarmi okundu olarak isaretler.
     *
     * @param email kullanici email adresi
     * @param id    alarm ID'si
     * @return 200 OK
     */
    @PutMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@RequestParam String email, @PathVariable Long id) {
        priceAlertService.markRead(resolveUserId(email), id);
        return ResponseEntity.ok().build();
    }

    /**
     * Kullanicinin tum okunmamis tetiklenmis alarmlarini toplu olarak okundu isaretler.
     *
     * @param email kullanici email adresi
     * @return guncellenen kayit sayisi
     */
    @PutMapping("/read-all")
    public ResponseEntity<Map<String, Integer>> markAllRead(@RequestParam String email) {
        int updated = priceAlertService.markAllRead(resolveUserId(email));
        return ResponseEntity.ok(Map.of("updatedCount", updated));
    }

    /**
     * Email adresinden kullanici ID'sini cikarir.
     *
     * @param email kullanici email adresi
     * @return kullanici ID'si
     * @throws RuntimeException kullanici bulunamazsa veya email bos ise
     */
    private Long resolveUserId(String email) {
        if (email == null || email.isBlank()) {
            throw new RuntimeException("Email parametresi zorunludur");
        }
        return appUserRepository.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new RuntimeException("Kullanici bulunamadi: " + email))
                .getId();
    }

    /**
     * RuntimeException'lari 400 Bad Request olarak doner.
     *
     * @param ex yakalanan calisma zamani hatasi
     * @return hata mesaji iceren JSON yanit
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntimeException(RuntimeException ex) {
        log.warn("Alarm islemi hatasi: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
