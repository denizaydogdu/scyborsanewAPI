package com.scyborsa.api.controller;

import com.scyborsa.api.dto.fintables.HedefFiyatDto;
import com.scyborsa.api.service.enrichment.HedefFiyatService;
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
 * Analist hedef fiyat ve tavsiye REST controller'ı.
 *
 * <p>Aracı kurum analist hedef fiyat verilerini sunar. Fintables MCP'den alınan ve
 * EnrichmentCache'de saklanan verileri DTO olarak döndürür.</p>
 *
 * @see HedefFiyatService
 * @see HedefFiyatDto
 */
@Validated
@Slf4j
@RestController
@RequestMapping("/api/v1/hedef-fiyat")
@RequiredArgsConstructor
public class HedefFiyatController {

    /** Hedef fiyat servis katmanı. */
    private final HedefFiyatService hedefFiyatService;

    /**
     * Tüm hedef fiyat verilerini getirir.
     *
     * <p>Yayın tarihine göre azalan sırada döner.
     * Cache'de yoksa MCP'den canlı çekilir (fallback).</p>
     *
     * @return hedef fiyat DTO listesi
     */
    @GetMapping
    public ResponseEntity<List<HedefFiyatDto>> getHedefFiyatlar() {
        List<HedefFiyatDto> result = hedefFiyatService.getHedefFiyatlar();
        return ResponseEntity.ok(result);
    }

    /**
     * Belirtilen hisse için hedef fiyat verilerini getirir.
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return ilgili hissenin hedef fiyat verileri
     */
    @GetMapping("/{stockCode}")
    public ResponseEntity<List<HedefFiyatDto>> getHisseHedefFiyatlar(
            @PathVariable @Pattern(regexp = "^[A-Z0-9]{2,6}$", message = "Geçersiz hisse kodu") String stockCode) {
        List<HedefFiyatDto> result = hedefFiyatService.getHisseHedefFiyatlar(stockCode);
        return ResponseEntity.ok(result);
    }
}
