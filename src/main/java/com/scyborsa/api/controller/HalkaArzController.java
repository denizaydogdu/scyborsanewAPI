package com.scyborsa.api.controller;

import com.scyborsa.api.dto.fintables.HalkaArzDto;
import com.scyborsa.api.service.enrichment.HalkaArzService;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Halka arz verileri REST controller'ı.
 *
 * <p>Halka arz verilerini sunar. Fintables MCP'den alınan ve
 * EnrichmentCache'de saklanan verileri DTO olarak döndürür.</p>
 *
 * @see HalkaArzService
 * @see HalkaArzDto
 */
@Validated
@Slf4j
@RestController
@RequestMapping("/api/v1/halka-arz")
@RequiredArgsConstructor
public class HalkaArzController {

    /** Halka arz servis katmanı. */
    private final HalkaArzService halkaArzService;

    /**
     * Tüm halka arz verilerini getirir.
     *
     * <p>Talep toplama başlangıç tarihine göre azalan sırada döner.
     * Cache'de yoksa MCP'den canlı çekilir (fallback).</p>
     *
     * @return halka arz DTO listesi
     */
    @GetMapping
    public ResponseEntity<List<HalkaArzDto>> getHalkaArzlar() {
        List<HalkaArzDto> result = halkaArzService.getHalkaArzlar();
        return ResponseEntity.ok(result);
    }

    /**
     * Aktif halka arzları getirir.
     *
     * <p>Tamamlanmamış ve iptal edilmemiş halka arzları döndürür.</p>
     *
     * @return aktif halka arz DTO listesi
     */
    @GetMapping("/aktif")
    public ResponseEntity<List<HalkaArzDto>> getAktifHalkaArzlar() {
        List<HalkaArzDto> result = halkaArzService.getAktifHalkaArzlar();
        return ResponseEntity.ok(result);
    }

    /**
     * Belirtilen hisse için halka arz verilerini getirir.
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return ilgili hissenin halka arz verileri
     */
    @GetMapping("/{stockCode}")
    public ResponseEntity<List<HalkaArzDto>> getHisseHalkaArz(
            @PathVariable @Pattern(regexp = "^[A-Z0-9]{2,6}$", message = "Geçersiz hisse kodu") String stockCode) {
        List<HalkaArzDto> result = halkaArzService.getHisseHalkaArz(stockCode);
        return ResponseEntity.ok(result);
    }
}
