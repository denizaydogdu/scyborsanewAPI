package com.scyborsa.api.controller;

import com.scyborsa.api.dto.fintables.FonPortfoyDto;
import com.scyborsa.api.service.enrichment.FonPortfoyService;
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
 * Fon portföy dağılım REST controller'ı.
 *
 * <p>Fon portföy dağılım verilerini sunar. Fintables MCP'den alınan ve
 * EnrichmentCache'de saklanan verileri DTO olarak döndürür.</p>
 *
 * @see FonPortfoyService
 * @see FonPortfoyDto
 */
@Validated
@Slf4j
@RestController
@RequestMapping("/api/v1/fon-portfoy")
@RequiredArgsConstructor
public class FonPortfoyController {

    /** Fon portföy servis katmanı. */
    private final FonPortfoyService fonPortfoyService;

    /**
     * Tüm fon portföy dağılım verilerini getirir.
     *
     * <p>Rapor ID'ye göre azalan sırada döner.
     * Cache'de yoksa MCP'den canlı çekilir (fallback).</p>
     *
     * @return fon portföy DTO listesi
     */
    @GetMapping
    public ResponseEntity<List<FonPortfoyDto>> getFonPortfoylar() {
        List<FonPortfoyDto> result = fonPortfoyService.getFonPortfoylar();
        return ResponseEntity.ok(result);
    }

    /**
     * Belirtilen hisseyi portföyünde tutan fonları getirir.
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return ilgili hisseyi tutan fonların portföy verileri
     */
    @GetMapping("/{stockCode}")
    public ResponseEntity<List<FonPortfoyDto>> getHisseFonlar(
            @PathVariable @Pattern(regexp = "^[A-Z0-9]{2,6}$", message = "Geçersiz hisse kodu") String stockCode) {
        List<FonPortfoyDto> result = fonPortfoyService.getHisseFonlar(stockCode);
        return ResponseEntity.ok(result);
    }
}
