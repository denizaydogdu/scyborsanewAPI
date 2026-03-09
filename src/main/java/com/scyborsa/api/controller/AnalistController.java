package com.scyborsa.api.controller;

import com.scyborsa.api.dto.AnalistDto;
import com.scyborsa.api.service.AnalistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Analist REST controller'i.
 *
 * <p>Analist CRUD endpoint'lerini sunar.
 * Tum endpoint'ler {@code /api/v1/analistler} prefix'i altindadir.</p>
 *
 * @see AnalistService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/analistler")
@RequiredArgsConstructor
public class AnalistController {

    private final AnalistService analistService;

    /**
     * Aktif analistleri listeler.
     *
     * <p>HTTP GET {@code /api/v1/analistler}</p>
     *
     * @return aktif analist listesi, siraNo'ya gore sirali
     */
    @GetMapping
    public ResponseEntity<List<AnalistDto>> getAnalistler() {
        List<AnalistDto> analistler = analistService.getAktifAnalistler();
        return ResponseEntity.ok(analistler);
    }

    /**
     * Tum analistleri (aktif + pasif) listeler.
     *
     * <p>HTTP GET {@code /api/v1/analistler/tumu}</p>
     *
     * @return tum analist listesi, siraNo'ya gore sirali
     */
    @GetMapping("/tumu")
    public ResponseEntity<List<AnalistDto>> getTumAnalistler() {
        List<AnalistDto> analistler = analistService.getTumAnalistler();
        return ResponseEntity.ok(analistler);
    }

    /**
     * ID'ye gore analist getirir.
     *
     * <p>HTTP GET {@code /api/v1/analistler/{id}}</p>
     *
     * @param id analist ID'si
     * @return analist bilgileri
     */
    @GetMapping("/{id}")
    public ResponseEntity<AnalistDto> getAnalistById(@PathVariable Long id) {
        AnalistDto analist = analistService.getAnalistById(id);
        return ResponseEntity.ok(analist);
    }

    /**
     * Yeni analist olusturur.
     *
     * <p>HTTP POST {@code /api/v1/analistler}</p>
     *
     * @param dto olusturulacak analist bilgileri
     * @return olusturulan analist, HTTP 201
     */
    @PostMapping
    public ResponseEntity<AnalistDto> createAnalist(@RequestBody AnalistDto dto) {
        log.info("[ANALIST] Analist olusturma istegi: {}", dto.getAd());
        AnalistDto created = analistService.createAnalist(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Mevcut analisti gunceller.
     *
     * <p>HTTP PUT {@code /api/v1/analistler/{id}}</p>
     *
     * @param id guncellenecek analist ID'si
     * @param dto yeni analist bilgileri
     * @return guncellenmis analist
     */
    @PutMapping("/{id}")
    public ResponseEntity<AnalistDto> updateAnalist(@PathVariable Long id, @RequestBody AnalistDto dto) {
        log.info("[ANALIST] Analist guncelleme istegi: id={}", id);
        AnalistDto updated = analistService.updateAnalist(id, dto);
        return ResponseEntity.ok(updated);
    }

    /**
     * Analisti soft delete yapar (aktif=false).
     *
     * <p>HTTP DELETE {@code /api/v1/analistler/{id}}</p>
     *
     * @param id silinecek analist ID'si
     * @return HTTP 204 No Content
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAnalist(@PathVariable Long id) {
        log.info("[ANALIST] Analist silme istegi: id={}", id);
        analistService.deleteAnalist(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Pasif analisti tekrar aktiflestirir.
     *
     * <p>HTTP PATCH {@code /api/v1/analistler/{id}/aktif}</p>
     *
     * @param id aktiflestirilecek analist ID'si
     * @return HTTP 204 No Content
     */
    @PatchMapping("/{id}/aktif")
    public ResponseEntity<Void> activateAnalist(@PathVariable Long id) {
        log.info("[ANALIST] Analist aktiflestirme istegi: id={}", id);
        analistService.activateAnalist(id);
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
}
