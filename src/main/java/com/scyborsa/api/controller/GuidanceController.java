package com.scyborsa.api.controller;

import com.scyborsa.api.dto.fintables.GuidanceDto;
import com.scyborsa.api.service.enrichment.GuidanceService;
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
 * Şirket guidance (beklenti) REST controller'ı.
 *
 * <p>Şirketlerin açıkladığı beklenti verilerini sunar. Fintables MCP'den alınan ve
 * EnrichmentCache'de saklanan verileri DTO olarak döndürür.</p>
 *
 * @see GuidanceService
 * @see GuidanceDto
 */
@Validated
@Slf4j
@RestController
@RequestMapping("/api/v1/guidance")
@RequiredArgsConstructor
public class GuidanceController {

    /** Guidance servis katmanı. */
    private final GuidanceService guidanceService;

    /**
     * Tüm guidance verilerini getirir.
     *
     * <p>Yıla göre azalan, hisse koduna göre artan sırada döner.
     * Cache'de yoksa MCP'den canlı çekilir (fallback).</p>
     *
     * @return guidance DTO listesi
     */
    @GetMapping
    public ResponseEntity<List<GuidanceDto>> getGuidancelar() {
        List<GuidanceDto> result = guidanceService.getGuidancelar();
        return ResponseEntity.ok(result);
    }

    /**
     * Belirtilen hisse için guidance verilerini getirir.
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return ilgili hissenin guidance verileri
     */
    @GetMapping("/{stockCode}")
    public ResponseEntity<List<GuidanceDto>> getHisseGuidance(
            @PathVariable @Pattern(regexp = "^[A-Z0-9]{2,6}$", message = "Geçersiz hisse kodu") String stockCode) {
        List<GuidanceDto> result = guidanceService.getHisseGuidance(stockCode);
        return ResponseEntity.ok(result);
    }
}
