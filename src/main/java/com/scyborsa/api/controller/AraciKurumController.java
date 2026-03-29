package com.scyborsa.api.controller;

import com.scyborsa.api.dto.AraciKurumDto;
import com.scyborsa.api.dto.enrichment.BrokerageAkdDetailResponseDto;
import com.scyborsa.api.dto.enrichment.BrokerageAkdListResponseDto;
import com.scyborsa.api.dto.enrichment.BrokerageTakasListResponseDto;
import com.scyborsa.api.service.AraciKurumService;
import com.scyborsa.api.service.enrichment.BrokerageAkdDetailService;
import com.scyborsa.api.service.enrichment.BrokerageAkdListService;
import com.scyborsa.api.service.enrichment.BrokerageTakasListService;
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
 * @see BrokerageAkdDetailService
 * @see BrokerageAkdListService
 * @see BrokerageTakasListService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/araci-kurumlar")
@RequiredArgsConstructor
public class AraciKurumController {

    private final AraciKurumService araciKurumService;
    private final BrokerageAkdDetailService brokerageAkdDetailService;
    private final BrokerageAkdListService brokerageAkdListService;
    private final BrokerageTakasListService brokerageTakasListService;

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
     * Piyasa geneli araci kurum AKD dagilim listesini dondurur.
     *
     * <p>HTTP GET {@code /api/v1/araci-kurumlar/akd-list}</p>
     *
     * @param date tarih filtresi (opsiyonel, YYYY-MM-DD)
     * @return AKD dagilim listesi
     */
    @GetMapping("/akd-list")
    public ResponseEntity<BrokerageAkdListResponseDto> getAkdList(
            @RequestParam(required = false) String date) {
        if (date != null && !date.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new IllegalArgumentException("Gecersiz tarih formati: YYYY-MM-DD bekleniyor");
        }
        BrokerageAkdListResponseDto response = brokerageAkdListService.getAkdList(date);
        return ResponseEntity.ok(response);
    }

    /**
     * Belirli bir araci kurumun hisse bazli AKD dagilimini dondurur.
     *
     * <p>HTTP GET {@code /api/v1/araci-kurumlar/{code}/akd-detail}</p>
     *
     * @param code kurum kodu (MLB, YKR vb.)
     * @param date opsiyonel tarih (YYYY-MM-DD formatinda)
     * @return hisse bazli AKD dagilim verisi
     */
    @GetMapping("/{code}/akd-detail")
    public ResponseEntity<BrokerageAkdDetailResponseDto> getAkdDetail(
            @PathVariable String code,
            @RequestParam(required = false) String date) {

        // code sanitizasyonu
        if (code == null || !code.matches("^[A-Z0-9]{2,10}$")) {
            return ResponseEntity.badRequest().build();
        }

        // date sanitizasyonu
        if (date != null && !date.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok(brokerageAkdDetailService.getAkdDetail(code, date));
    }

    /**
     * Piyasa geneli araci kurum takas (saklama) listesini dondurur.
     *
     * <p>HTTP GET {@code /api/v1/araci-kurumlar/takas-list}</p>
     *
     * @return takas (saklama) dagilim listesi
     */
    @GetMapping("/takas-list")
    public ResponseEntity<BrokerageTakasListResponseDto> getTakasList() {
        BrokerageTakasListResponseDto response = brokerageTakasListService.getTakasList();
        return ResponseEntity.ok(response);
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
