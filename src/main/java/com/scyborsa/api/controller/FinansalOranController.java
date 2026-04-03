package com.scyborsa.api.controller;

import com.scyborsa.api.dto.fintables.FinansalOranDto;
import com.scyborsa.api.service.enrichment.FinansalOranService;
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
 * Finansal oranlar REST controller'ı.
 *
 * <p>Finansal oran verilerini (F/K, PD/DD, ROE vb.) sunar. Fintables MCP'den
 * alınan ve EnrichmentCache'de saklanan verileri DTO olarak döndürür.</p>
 *
 * @see FinansalOranService
 * @see FinansalOranDto
 */
@Validated
@Slf4j
@RestController
@RequestMapping("/api/v1/finansal-oran")
@RequiredArgsConstructor
public class FinansalOranController {

    /** Finansal oran servis katmanı. */
    private final FinansalOranService finansalOranService;

    /**
     * Tüm finansal oranları getirir.
     *
     * <p>Hisse kodu, yıl DESC, ay DESC sıralı döner.
     * Cache'de yoksa MCP'den canlı çekilir (fallback).</p>
     *
     * @return finansal oran DTO listesi
     */
    @GetMapping
    public ResponseEntity<List<FinansalOranDto>> getFinansalOranlar() {
        List<FinansalOranDto> result = finansalOranService.getFinansalOranlar();
        return ResponseEntity.ok(result);
    }

    /**
     * Belirtilen hisse için finansal oranları getirir.
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return ilgili hissenin finansal oranları
     */
    @GetMapping("/{stockCode}")
    public ResponseEntity<List<FinansalOranDto>> getHisseOranlar(
            @PathVariable @Pattern(regexp = "^[A-Z0-9]{2,6}$", message = "Geçersiz hisse kodu") String stockCode) {
        List<FinansalOranDto> result = finansalOranService.getHisseOranlar(stockCode);
        return ResponseEntity.ok(result);
    }
}
