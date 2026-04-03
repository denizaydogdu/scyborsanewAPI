package com.scyborsa.api.controller;

import com.scyborsa.api.dto.fintables.AcigaSatisDto;
import com.scyborsa.api.service.enrichment.AcigaSatisService;
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
 * Açığa satış istatistikleri REST controller'ı.
 *
 * <p>Günlük açığa satış verilerini sunar. Fintables MCP'den alınan ve
 * EnrichmentCache'de saklanan verileri DTO olarak döndürür.</p>
 *
 * @see AcigaSatisService
 * @see AcigaSatisDto
 */
@Validated
@Slf4j
@RestController
@RequestMapping("/api/v1/aciga-satis")
@RequiredArgsConstructor
public class AcigaSatisController {

    /** Açığa satış servis katmanı. */
    private final AcigaSatisService acigaSatisService;

    /**
     * Tüm günlük açığa satış verilerini getirir.
     *
     * <p>Açığa satış hacmi (TL) azalan sırada döner.
     * Cache'de yoksa MCP'den canlı çekilir (fallback).</p>
     *
     * @return açığa satış DTO listesi
     */
    @GetMapping
    public ResponseEntity<List<AcigaSatisDto>> getGunlukAcigaSatislar() {
        List<AcigaSatisDto> result = acigaSatisService.getGunlukAcigaSatislar();
        return ResponseEntity.ok(result);
    }

    /**
     * Belirtilen hisse için günlük açığa satış verilerini getirir.
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return ilgili hissenin açığa satış verileri
     */
    @GetMapping("/{stockCode}")
    public ResponseEntity<List<AcigaSatisDto>> getHisseAcigaSatis(
            @PathVariable @Pattern(regexp = "^[A-Z0-9]{2,6}$", message = "Geçersiz hisse kodu") String stockCode) {
        List<AcigaSatisDto> result = acigaSatisService.getHisseAcigaSatis(stockCode);
        return ResponseEntity.ok(result);
    }
}
