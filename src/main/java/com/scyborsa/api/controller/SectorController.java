package com.scyborsa.api.controller;

import com.scyborsa.api.config.SectorDefinitionRegistry;
import com.scyborsa.api.dto.sector.SectorDefinitionDto;
import com.scyborsa.api.dto.sector.SectorStockDto;
import com.scyborsa.api.dto.sector.SectorSummaryDto;
import com.scyborsa.api.service.SectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Sektor bazli hisse verilerine erisim saglayan REST controller.
 *
 * <p>Uc endpoint sunar:</p>
 * <ul>
 *   <li>{@code GET /api/v1/sector} - Tum sektor tanimlarini listeler</li>
 *   <li>{@code GET /api/v1/sector/summaries} - Sektor ozetlerini (hisse sayisi + ort. degisim) dondurur</li>
 *   <li>{@code GET /api/v1/sector/{slug}} - Belirtilen sektorun hisse listesini dondurur</li>
 * </ul>
 *
 * <p>Legacy slug destegi: Eski slug'lar (orn. "banks") otomatik olarak
 * yeni kanonik slug'a (orn. "bankacilik") yonlendirilir.</p>
 *
 * @see SectorService
 * @see SectorDefinitionRegistry
 * @see SectorDefinitionDto
 * @see SectorStockDto
 * @see SectorSummaryDto
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sector")
@RequiredArgsConstructor
public class SectorController {

    private final SectorService sectorService;
    private final SectorDefinitionRegistry registry;

    /**
     * Tum sektor tanimlarini siraNo'ya gore sirali olarak dondurur.
     *
     * @return sektor tanim listesi (slug, displayName, description, icon)
     */
    @GetMapping
    public ResponseEntity<List<SectorDefinitionDto>> getAllSectors() {
        List<SectorDefinitionDto> sectors = registry.getAll();
        return ResponseEntity.ok(sectors);
    }

    /**
     * Tum sektorlerin ozet istatistiklerini dondurur.
     *
     * <p>Her sektor icin hisse sayisi ve ortalama degisim yuzdesi
     * hesaplanarak dondurulur. Sonuclar ortalama degisim yuzdesine
     * gore azalan sirada siralanir.</p>
     *
     * @return sektor ozet listesi; hata durumunda bos liste
     */
    @GetMapping("/summaries")
    public ResponseEntity<List<SectorSummaryDto>> getSectorSummaries() {
        List<SectorSummaryDto> summaries = sectorService.getSectorSummaries();
        return ResponseEntity.ok(summaries);
    }

    /**
     * Belirtilen sektor endeksine ait hisse listesini dondurur.
     *
     * <p>Legacy slug destegi: eger verilen slug bir legacy slug ise
     * (orn. "banks"), kanonik slug bilgisi header'da dondurulur.</p>
     *
     * @param slug sektor slug degeri (orn. "bankacilik" veya legacy "banks")
     * @return sektor hisse listesi; gecersiz slug icin 404
     */
    @GetMapping("/{slug}")
    public ResponseEntity<?> getSectorStocks(@PathVariable String slug) {
        if (slug == null || slug.length() > 50) {
            return ResponseEntity.notFound().build();
        }

        // Log sanitization
        String sanitizedSlug = slug.replaceAll("[\\r\\n]", "_");

        // Legacy slug kontrolu: eger legacy ise kanonik slug'a yonlendir
        String canonicalSlug = registry.resolveSlug(slug);
        if (canonicalSlug == null) {
            log.warn("[SECTOR-CONTROLLER] Gecersiz sektor slug: {}", sanitizedSlug);
            return ResponseEntity.notFound().build();
        }

        // Legacy slug yonlendirmesi (header ile bilgilendir)
        if (!slug.equals(canonicalSlug)) {
            log.info("[SECTOR-CONTROLLER] Legacy slug yonlendirmesi: {} -> {}", sanitizedSlug, canonicalSlug);
            List<SectorStockDto> stocks = sectorService.getSectorStocks(canonicalSlug);
            return ResponseEntity.ok()
                    .header("X-Canonical-Slug", canonicalSlug)
                    .body(Map.of(
                            "canonicalSlug", canonicalSlug,
                            "stocks", stocks
                    ));
        }

        List<SectorStockDto> stocks = sectorService.getSectorStocks(slug);
        return ResponseEntity.ok(stocks);
    }
}
