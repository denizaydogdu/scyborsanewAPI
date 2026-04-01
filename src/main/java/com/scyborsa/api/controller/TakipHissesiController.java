package com.scyborsa.api.controller;

import com.scyborsa.api.dto.takiphissesi.TakipHissesiDto;
import com.scyborsa.api.dto.takiphissesi.TakipHissesiRequest;
import com.scyborsa.api.enums.YatirimVadesi;
import com.scyborsa.api.service.TakipHissesiService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Takip hissesi (hisse onerisi) REST controller'i.
 *
 * <p>Hisse onerisi CRUD endpoint'lerini sunar.
 * Tum endpoint'ler {@code /api/v1/takip-hisseleri} prefix'i altindadir.</p>
 *
 * @see TakipHissesiService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/takip-hisseleri")
@RequiredArgsConstructor
public class TakipHissesiController {

    private final TakipHissesiService takipHissesiService;

    /**
     * Aktif takip hisselerini listeler. Opsiyonel vade filtresi destekler.
     *
     * <p>HTTP GET {@code /api/v1/takip-hisseleri}
     * <br>HTTP GET {@code /api/v1/takip-hisseleri?vade=KISA_VADE}</p>
     *
     * @param vade opsiyonel vade filtresi (KISA_VADE, ORTA_VADE, UZUN_VADE)
     * @return aktif takip hissesi listesi, guncel fiyat zenginlestirmesi ile
     */
    @GetMapping
    public ResponseEntity<List<TakipHissesiDto>> getTakipHisseleri(
            @RequestParam(required = false) YatirimVadesi vade) {
        List<TakipHissesiDto> result;
        if (vade != null) {
            result = takipHissesiService.getAktifTakipHisseleriByVade(vade);
        } else {
            result = takipHissesiService.getAktifTakipHisseleri();
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Tum takip hisselerini (aktif + pasif) listeler. Backoffice icin.
     *
     * <p>HTTP GET {@code /api/v1/takip-hisseleri/tumu}</p>
     *
     * @return tum takip hissesi listesi, siraNo'ya gore sirali
     */
    @GetMapping("/tumu")
    public ResponseEntity<List<TakipHissesiDto>> getTumTakipHisseleri() {
        List<TakipHissesiDto> result = takipHissesiService.getTumTakipHisseleri();
        return ResponseEntity.ok(result);
    }

    /**
     * ID'ye gore takip hissesi getirir.
     *
     * <p>HTTP GET {@code /api/v1/takip-hisseleri/{id}}</p>
     *
     * @param id takip hissesi ID'si
     * @return takip hissesi bilgileri, guncel fiyat zenginlestirmesi ile
     */
    @GetMapping("/{id}")
    public ResponseEntity<TakipHissesiDto> getTakipHissesiById(@PathVariable Long id) {
        TakipHissesiDto result = takipHissesiService.getTakipHissesiById(id);
        return ResponseEntity.ok(result);
    }

    /**
     * Yeni takip hissesi onerisi olusturur.
     *
     * <p>HTTP POST {@code /api/v1/takip-hisseleri}</p>
     *
     * @param request olusturma istegi
     * @return olusturulan takip hissesi, HTTP 201
     */
    @PostMapping
    public ResponseEntity<TakipHissesiDto> createTakipHissesi(@Valid @RequestBody TakipHissesiRequest request) {
        log.info("[TAKIP-HISSESI] Olusturma istegi: hisseKodu={}, vade={}", request.getHisseKodu(), request.getVade());
        TakipHissesiDto created = takipHissesiService.createTakipHissesi(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Mevcut takip hissesi onerisini gunceller.
     *
     * <p>HTTP PUT {@code /api/v1/takip-hisseleri/{id}}</p>
     *
     * @param id      guncellenecek kayit ID'si
     * @param request guncelleme istegi
     * @return guncellenmis takip hissesi
     */
    @PutMapping("/{id}")
    public ResponseEntity<TakipHissesiDto> updateTakipHissesi(
            @PathVariable Long id,
            @Valid @RequestBody TakipHissesiRequest request) {
        log.info("[TAKIP-HISSESI] Guncelleme istegi: id={}", id);
        TakipHissesiDto updated = takipHissesiService.updateTakipHissesi(id, request);
        return ResponseEntity.ok(updated);
    }

    /**
     * Takip hissesi onerisini soft delete yapar (aktif=false).
     *
     * <p>HTTP DELETE {@code /api/v1/takip-hisseleri/{id}}</p>
     *
     * @param id silinecek kayit ID'si
     * @return HTTP 204 No Content
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTakipHissesi(@PathVariable Long id) {
        log.info("[TAKIP-HISSESI] Silme istegi: id={}", id);
        takipHissesiService.deleteTakipHissesi(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Pasif takip hissesi onerisini tekrar aktiflestirir.
     *
     * <p>HTTP PATCH {@code /api/v1/takip-hisseleri/{id}/aktif}</p>
     *
     * @param id aktiflestirilecek kayit ID'si
     * @return HTTP 204 No Content
     */
    @PatchMapping("/{id}/aktif")
    public ResponseEntity<Void> activateTakipHissesi(@PathVariable Long id) {
        log.info("[TAKIP-HISSESI] Aktiflestirme istegi: id={}", id);
        takipHissesiService.activateTakipHissesi(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * {@link IllegalArgumentException} hatalarini HTTP 400 Bad Request olarak doner.
     *
     * @param ex yakalanan istisna
     * @return hata mesaji ile HTTP 400 yaniti
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }

    /**
     * Bean Validation hatalarını HTTP 400 Bad Request olarak döner.
     *
     * @param ex yakalanan validasyon istisnası
     * @return alan bazlı hata mesajları ile HTTP 400 yanıtı
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<String> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(message);
    }
}
