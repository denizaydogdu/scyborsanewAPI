package com.scyborsa.api.controller;

import com.scyborsa.api.dto.AraciKurumDto;
import com.scyborsa.api.service.AraciKurumService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Araci kurum REST controller'i.
 *
 * <p>Araci kurum listeleme ve detay endpoint'lerini sunar.
 * Tum endpoint'ler {@code /api/v1/araci-kurumlar} prefix'i altindadir.</p>
 *
 * @see AraciKurumService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/araci-kurumlar")
@RequiredArgsConstructor
public class AraciKurumController {

    private final AraciKurumService araciKurumService;

    /**
     * Aktif araci kurumlari listeler.
     *
     * <p>HTTP GET {@code /api/v1/araci-kurumlar}</p>
     *
     * @return aktif araci kurum listesi, siraNo'ya gore sirali
     */
    @GetMapping
    public ResponseEntity<List<AraciKurumDto>> getAktifAraciKurumlar() {
        List<AraciKurumDto> kurumlar = araciKurumService.getAktifAraciKurumlar();
        return ResponseEntity.ok(kurumlar);
    }

    /**
     * Koda gore araci kurum getirir.
     *
     * <p>HTTP GET {@code /api/v1/araci-kurumlar/{code}}</p>
     *
     * @param code araci kurum kodu
     * @return araci kurum bilgileri
     */
    @GetMapping("/{code}")
    public ResponseEntity<AraciKurumDto> getAraciKurumByCode(@PathVariable String code) {
        AraciKurumDto kurum = araciKurumService.getAraciKurumByCode(code);
        return ResponseEntity.ok(kurum);
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
