package com.scyborsa.api.controller;

import com.scyborsa.api.dto.fintables.TarihselAnalizDto;
import com.scyborsa.api.service.enrichment.TarihselAnalizService;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Tarihsel analiz REST controller'ı.
 *
 * <p>F/K bandı, gelir/net kâr CAGR ve net borç trendi
 * verilerini döndüren endpoint'leri sağlar.</p>
 *
 * <p><b>Base path:</b> {@code /api/v1/tarihsel-analiz}</p>
 *
 * @see TarihselAnalizService
 * @see TarihselAnalizDto
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/tarihsel-analiz")
@RequiredArgsConstructor
public class TarihselAnalizController {

    private final TarihselAnalizService tarihselAnalizService;

    /**
     * Belirtilen hisse için tarihsel analiz verisini döndürür.
     *
     * <p>F/K bandı, gelir CAGR, net kâr CAGR ve net borç trendi bağımsız olarak
     * hesaplanır. Hesaplanamayan bileşenler {@code null} olarak döner.</p>
     *
     * @param stockCode hisse kodu (ör: "GARAN"), 1-10 karakter büyük harf/rakam
     * @return {@link ResponseEntity} içinde {@link TarihselAnalizDto}
     */
    @GetMapping("/{stockCode}")
    public ResponseEntity<TarihselAnalizDto> getTarihselAnaliz(
            @PathVariable @Pattern(regexp = "^[A-Z0-9]{1,10}$",
                    message = "Hisse kodu 1-10 karakter, büyük harf ve rakam olmalıdır")
            String stockCode) {
        log.debug("[TARIHSEL-ANALIZ] Analiz isteği: stockCode={}", stockCode);
        TarihselAnalizDto result = tarihselAnalizService.getTarihselAnaliz(stockCode);
        return ResponseEntity.ok(result);
    }
}
