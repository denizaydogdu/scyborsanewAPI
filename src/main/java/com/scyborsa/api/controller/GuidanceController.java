package com.scyborsa.api.controller;

import com.scyborsa.api.dto.fintables.GuidanceDto;
import com.scyborsa.api.service.enrichment.GuidanceService;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Sirket guidance (beklenti) REST controller'i.
 *
 * <p>Sirketlerin acikladi beklenti verilerini sunar. Fintables MCP'den alinan ve
 * EnrichmentCache'de saklanan verileri DTO olarak dondurur.</p>
 *
 * <p>Liste endpoint'i sadece hisse+yil dondurur (beklentiler yok).
 * Detay endpoint'i belirtilen hisse icin ham beklenti metnini MCP'den canli sorgular.</p>
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

    /** Guidance servis katmani. */
    private final GuidanceService guidanceService;

    /**
     * Tum guidance verilerini getirir (sadece hisse kodu + yil, beklentiler yok).
     *
     * <p>Yila gore azalan, hisse koduna gore artan sirada doner.
     * Cache'de yoksa MCP'den canli cekilir (fallback).</p>
     *
     * @return guidance DTO listesi (beklentiler=null)
     */
    @GetMapping
    public ResponseEntity<List<GuidanceDto>> getGuidancelar() {
        List<GuidanceDto> result = guidanceService.getGuidancelar();
        return ResponseEntity.ok(result);
    }

    /**
     * Belirtilen hisse icin raw guidance metnini getirir.
     *
     * <p>Dogrudan MCP sorgusu yapar, beklentiler alanini ham metin olarak dondurur.
     * Parse ETMEZ; UI tarafinda JS ile render edilir.</p>
     *
     * @param stockCode hisse kodu (or: "THYAO")
     * @return ham markdown tablo metni veya 204 No Content
     */
    @GetMapping(value = "/{stockCode}/raw", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getRawGuidance(
            @PathVariable @Pattern(regexp = "^[A-Z0-9]{1,10}$", message = "Gecersiz hisse kodu") String stockCode) {
        String rawText = guidanceService.getRawGuidance(stockCode);
        if (rawText == null || rawText.isBlank()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(rawText);
    }
}
