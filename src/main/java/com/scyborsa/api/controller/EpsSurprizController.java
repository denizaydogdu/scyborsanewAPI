package com.scyborsa.api.controller;

import com.scyborsa.api.dto.fintables.EpsSurprizDto;
import com.scyborsa.api.service.enrichment.EpsSurprizService;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * EPS sürpriz REST controller'ı.
 *
 * <p>EPS sürpriz oranları, payout ratio, FCF yield ve temettü
 * sürdürülebilirlik verilerini döndüren endpoint'leri sağlar.</p>
 *
 * <p><b>Base path:</b> {@code /api/v1/eps-surpriz}</p>
 *
 * @see EpsSurprizService
 * @see EpsSurprizDto
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/eps-surpriz")
@RequiredArgsConstructor
public class EpsSurprizController {

    private final EpsSurprizService epsSurprizService;

    /**
     * Belirtilen hisse için EPS sürpriz ve temettü sürdürülebilirlik verisini döndürür.
     *
     * <p>EPS sürpriz dönemleri, payout ratio, FCF yield ve sürdürülebilirlik
     * değerlendirmesi bağımsız olarak hesaplanır. Hesaplanamayan bileşenler
     * {@code null} olarak döner.</p>
     *
     * @param stockCode hisse kodu (ör: "GARAN"), 1-10 karakter büyük harf/rakam
     * @return {@link ResponseEntity} içinde {@link EpsSurprizDto}
     */
    @GetMapping("/{stockCode}")
    public ResponseEntity<EpsSurprizDto> getEpsSurpriz(
            @PathVariable @Pattern(regexp = "^[A-Z0-9]{1,10}$",
                    message = "Hisse kodu 1-10 karakter, büyük harf ve rakam olmalıdır")
            String stockCode) {
        log.debug("[EPS-SURPRIZ] Sürpriz isteği: stockCode={}", stockCode);
        EpsSurprizDto result = epsSurprizService.getEpsSurpriz(stockCode);
        return ResponseEntity.ok(result);
    }
}
