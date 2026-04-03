package com.scyborsa.api.controller;

import com.scyborsa.api.dto.fintables.FinansalTabloDto;
import com.scyborsa.api.service.enrichment.FinansalTabloService;
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
 * Finansal tablo (bilanço, gelir tablosu, nakit akım) REST controller'ı.
 *
 * <p>Fintables MCP'den alınan ve EnrichmentCache'de saklanan finansal tablo
 * verilerini DTO olarak döndürür. Her hisse için bilanço kalemleri, gelir
 * tablosu kalemleri ve nakit akım tablosu kalemleri ayrı endpoint'lerden sunulur.</p>
 *
 * @see FinansalTabloService
 * @see FinansalTabloDto
 */
@Validated
@Slf4j
@RestController
@RequestMapping("/api/v1/finansal-tablo")
@RequiredArgsConstructor
public class FinansalTabloController {

    /** Finansal tablo servis katmanı. */
    private final FinansalTabloService finansalTabloService;

    /**
     * Belirtilen hissenin bilanço kalemlerini getirir.
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return bilanço kalemleri DTO listesi
     */
    @GetMapping("/{stockCode}/bilanco")
    public ResponseEntity<List<FinansalTabloDto>> getHisseBilanco(
            @PathVariable @Pattern(regexp = "^[A-Z0-9]{2,6}$", message = "Geçersiz hisse kodu") String stockCode) {
        List<FinansalTabloDto> result = finansalTabloService.getHisseBilanco(stockCode);
        return ResponseEntity.ok(result);
    }

    /**
     * Belirtilen hissenin gelir tablosu kalemlerini getirir.
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return gelir tablosu kalemleri DTO listesi
     */
    @GetMapping("/{stockCode}/gelir")
    public ResponseEntity<List<FinansalTabloDto>> getHisseGelirTablosu(
            @PathVariable @Pattern(regexp = "^[A-Z0-9]{2,6}$", message = "Geçersiz hisse kodu") String stockCode) {
        List<FinansalTabloDto> result = finansalTabloService.getHisseGelirTablosu(stockCode);
        return ResponseEntity.ok(result);
    }

    /**
     * Belirtilen hissenin nakit akım tablosu kalemlerini getirir.
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return nakit akım kalemleri DTO listesi
     */
    @GetMapping("/{stockCode}/nakit-akim")
    public ResponseEntity<List<FinansalTabloDto>> getHisseNakitAkim(
            @PathVariable @Pattern(regexp = "^[A-Z0-9]{2,6}$", message = "Geçersiz hisse kodu") String stockCode) {
        List<FinansalTabloDto> result = finansalTabloService.getHisseNakitAkim(stockCode);
        return ResponseEntity.ok(result);
    }
}
