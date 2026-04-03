package com.scyborsa.api.controller;

import com.scyborsa.api.dto.fintables.TemelAnalizSkorDto;
import com.scyborsa.api.service.enrichment.TemelAnalizSkorService;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Temel analiz skor REST controller'ı.
 *
 * <p>Altman Z-Score, Piotroski F-Score ve Graham Sayısı
 * hesaplama sonuçlarını döndüren endpoint'leri sağlar.</p>
 *
 * <p><b>Base path:</b> {@code /api/v1/temel-analiz-skor}</p>
 *
 * @see TemelAnalizSkorService
 * @see TemelAnalizSkorDto
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/temel-analiz-skor")
@RequiredArgsConstructor
public class TemelAnalizSkorController {

    private final TemelAnalizSkorService temelAnalizSkorService;

    /**
     * Belirtilen hisse için temel analiz skorlarını döndürür.
     *
     * <p>Altman Z-Score, Piotroski F-Score ve Graham Sayısı bağımsız olarak
     * hesaplanır. Hesaplanamayan bileşenler {@code null} olarak döner.</p>
     *
     * @param stockCode hisse kodu (ör: "GARAN"), 1-10 karakter büyük harf/rakam
     * @return {@link ResponseEntity} içinde {@link TemelAnalizSkorDto} — Altman Z-Score, Piotroski F-Score ve Graham Sayısı (hesaplanamayanlar null)
     */
    @GetMapping("/{stockCode}")
    public ResponseEntity<TemelAnalizSkorDto> getScores(
            @PathVariable @Pattern(regexp = "^[A-Z0-9]{1,10}$",
                    message = "Hisse kodu 1-10 karakter, büyük harf ve rakam olmalıdır")
            String stockCode) {
        log.debug("[TEMEL-ANALIZ-SKOR] Skor hesaplama isteği: stockCode={}", stockCode);
        TemelAnalizSkorDto result = temelAnalizSkorService.calculateScores(stockCode);
        return ResponseEntity.ok(result);
    }
}
