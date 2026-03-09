package com.scyborsa.api.controller;

import com.scyborsa.api.dto.ModelPortfoyKurumDto;
import com.scyborsa.api.service.ModelPortfoyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Model portföy REST controller'ı.
 *
 * <p>Aracı kurum CRUD endpoint'lerini sunar.
 * Tüm endpoint'ler {@code /api/v1/model-portfoy} prefix'i altındadır.</p>
 *
 * @see ModelPortfoyService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/model-portfoy")
@RequiredArgsConstructor
public class ModelPortfoyController {

    private final ModelPortfoyService modelPortfoyService;

    /**
     * Aktif aracı kurumları listeler.
     *
     * <p>HTTP GET {@code /api/v1/model-portfoy/kurumlar}</p>
     *
     * @return aktif kurum listesi, siraNo'ya göre sıralı
     */
    @GetMapping("/kurumlar")
    public ResponseEntity<List<ModelPortfoyKurumDto>> getKurumlar() {
        List<ModelPortfoyKurumDto> kurumlar = modelPortfoyService.getAktifKurumlar();
        return ResponseEntity.ok(kurumlar);
    }

    /**
     * Tum kurumlari (aktif + pasif) listeler.
     *
     * <p>HTTP GET {@code /api/v1/model-portfoy/kurumlar/tumu}</p>
     *
     * @return tum kurum listesi, siraNo'ya gore sirali
     */
    @GetMapping("/kurumlar/tumu")
    public ResponseEntity<List<ModelPortfoyKurumDto>> getTumKurumlar() {
        List<ModelPortfoyKurumDto> kurumlar = modelPortfoyService.getTumKurumlar();
        return ResponseEntity.ok(kurumlar);
    }

    /**
     * ID'ye göre kurum getirir.
     *
     * <p>HTTP GET {@code /api/v1/model-portfoy/kurumlar/{id}}</p>
     *
     * @param id kurum ID'si
     * @return kurum bilgileri
     */
    @GetMapping("/kurumlar/{id}")
    public ResponseEntity<ModelPortfoyKurumDto> getKurumById(@PathVariable Long id) {
        ModelPortfoyKurumDto kurum = modelPortfoyService.getKurumById(id);
        return ResponseEntity.ok(kurum);
    }

    /**
     * Yeni aracı kurum oluşturur.
     *
     * <p>HTTP POST {@code /api/v1/model-portfoy/kurumlar}</p>
     *
     * @param dto oluşturulacak kurum bilgileri
     * @return oluşturulan kurum, HTTP 201
     */
    @PostMapping("/kurumlar")
    public ResponseEntity<ModelPortfoyKurumDto> createKurum(@RequestBody ModelPortfoyKurumDto dto) {
        log.info("[MODEL-PORTFOY] Kurum oluşturma isteği: {}", dto.getKurumAdi());
        ModelPortfoyKurumDto created = modelPortfoyService.createKurum(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Mevcut kurumu günceller.
     *
     * <p>HTTP PUT {@code /api/v1/model-portfoy/kurumlar/{id}}</p>
     *
     * @param id güncellenecek kurum ID'si
     * @param dto yeni kurum bilgileri
     * @return güncellenmiş kurum
     */
    @PutMapping("/kurumlar/{id}")
    public ResponseEntity<ModelPortfoyKurumDto> updateKurum(@PathVariable Long id, @RequestBody ModelPortfoyKurumDto dto) {
        log.info("[MODEL-PORTFOY] Kurum güncelleme isteği: id={}", id);
        ModelPortfoyKurumDto updated = modelPortfoyService.updateKurum(id, dto);
        return ResponseEntity.ok(updated);
    }

    /**
     * Kurumu soft delete yapar (aktif=false).
     *
     * <p>HTTP DELETE {@code /api/v1/model-portfoy/kurumlar/{id}}</p>
     *
     * @param id silinecek kurum ID'si
     * @return HTTP 204 No Content
     */
    @DeleteMapping("/kurumlar/{id}")
    public ResponseEntity<Void> deleteKurum(@PathVariable Long id) {
        log.info("[MODEL-PORTFOY] Kurum silme isteği: id={}", id);
        modelPortfoyService.deleteKurum(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Pasif kurumu tekrar aktifleştirir.
     *
     * <p>HTTP PATCH {@code /api/v1/model-portfoy/kurumlar/{id}/aktif}</p>
     *
     * @param id aktifleştirilecek kurum ID'si
     * @return HTTP 204 No Content
     */
    @PatchMapping("/kurumlar/{id}/aktif")
    public ResponseEntity<Void> activateKurum(@PathVariable Long id) {
        log.info("[MODEL-PORTFOY] Kurum aktifleştirme isteği: id={}", id);
        modelPortfoyService.activateKurum(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * {@link IllegalArgumentException} hatalarını HTTP 400 Bad Request olarak döner.
     *
     * @param ex yakalanan istisna
     * @return hata mesajı ile HTTP 400 yanıtı
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
