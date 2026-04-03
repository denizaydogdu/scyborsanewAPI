package com.scyborsa.api.controller;

import com.scyborsa.api.dto.fintables.KapHaberSinyalDto;
import com.scyborsa.api.service.enrichment.KapHaberSinyalService;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * KAP haber sinyal REST controller'ı.
 *
 * <p>Son 30 günün KAP haberlerinden türetilen sinyalleri
 * (sermaye artırımı, temettü, geri alım vb.) döndüren endpoint'leri sağlar.</p>
 *
 * <p><b>Base path:</b> {@code /api/v1/kap-sinyal}</p>
 *
 * @see KapHaberSinyalService
 * @see KapHaberSinyalDto
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/kap-sinyal")
@RequiredArgsConstructor
public class KapHaberSinyalController {

    private final KapHaberSinyalService kapHaberSinyalService;

    /**
     * Belirtilen hisse için KAP haber sinyallerini döndürür.
     *
     * <p>Son 30 günün KAP haberlerini MCP üzerinden çeker ve keyword
     * bazlı sınıflandırma ile sinyaller üretir.</p>
     *
     * @param stockCode hisse kodu (ör: "GARAN"), 1-10 karakter büyük harf/rakam
     * @return {@link ResponseEntity} içinde {@link KapHaberSinyalDto} listesi (boş olabilir)
     */
    @GetMapping("/{stockCode}")
    public ResponseEntity<List<KapHaberSinyalDto>> getKapSinyaller(
            @PathVariable @Pattern(regexp = "^[A-Z0-9]{1,10}$",
                    message = "Hisse kodu 1-10 karakter, büyük harf ve rakam olmalıdır")
            String stockCode) {
        log.debug("[KAP-SINYAL] Sinyal isteği: stockCode={}", stockCode);
        List<KapHaberSinyalDto> result = kapHaberSinyalService.getKapSinyaller(stockCode);
        return ResponseEntity.ok(result);
    }
}
