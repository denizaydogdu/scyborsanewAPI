package com.scyborsa.api.controller;

import com.scyborsa.api.service.enrichment.SirketRaporService;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Şirket raporu REST controller'ı.
 *
 * <p>Belirtilen hisse için markdown formatında şirket raporu döndüren
 * endpoint sağlar. Claude API entegrasyonu şimdilik mevcut değildir;
 * rapor şablona dayalı olarak üretilir.</p>
 *
 * <p><b>Base path:</b> {@code /api/v1/sirket-rapor}</p>
 *
 * @see SirketRaporService
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/sirket-rapor")
@RequiredArgsConstructor
public class SirketRaporController {

    private final SirketRaporService sirketRaporService;

    /**
     * Belirtilen hisse için markdown formatında şirket raporu döndürür.
     *
     * @param stockCode hisse kodu (ör: "GARAN"), 1-10 karakter büyük harf/rakam
     * @return {@link ResponseEntity} içinde markdown rapor string'i
     */
    @GetMapping(value = "/{stockCode}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getRapor(
            @PathVariable @Pattern(regexp = "^[A-Z0-9]{1,10}$",
                    message = "Hisse kodu 1-10 karakter, büyük harf ve rakam olmalıdır")
            String stockCode) {
        log.debug("[SIRKET-RAPOR] Rapor isteği: stockCode={}", stockCode);
        String rapor = sirketRaporService.generateRapor(stockCode);
        return ResponseEntity.ok(rapor);
    }
}
