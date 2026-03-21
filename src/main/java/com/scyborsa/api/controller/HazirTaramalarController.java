package com.scyborsa.api.controller;

import com.scyborsa.api.dto.market.HazirTaramalarResponseDto;
import com.scyborsa.api.dto.market.PresetStrategyDto;
import com.scyborsa.api.service.market.HazirTaramalarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Hazir tarama stratejilerine erisim saglayan REST controller.
 *
 * <p>Iki endpoint sunar:</p>
 * <ul>
 *   <li>{@code GET /api/v1/hazir-taramalar/strategies} — Tum mevcut stratejileri listeler</li>
 *   <li>{@code GET /api/v1/hazir-taramalar/scan?strategy=xxx} — Belirtilen stratejiyi calistirir</li>
 * </ul>
 *
 * @see HazirTaramalarService
 * @see PresetStrategyDto
 * @see HazirTaramalarResponseDto
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/hazir-taramalar")
@RequiredArgsConstructor
public class HazirTaramalarController {

    /** Hazir tarama is mantigi servisi. */
    private final HazirTaramalarService hazirTaramalarService;

    /**
     * Tum mevcut hazir tarama stratejilerini dondurur.
     *
     * @return strateji tanim listesi (kod, gorunen ad, aciklama, kategori)
     */
    @GetMapping("/strategies")
    public ResponseEntity<List<PresetStrategyDto>> getStrategies() {
        List<PresetStrategyDto> strategies = hazirTaramalarService.getStrategies();
        return ResponseEntity.ok(strategies);
    }

    /**
     * Belirtilen strateji koduna gore tarama yapar ve eslesen hisseleri dondurur.
     *
     * <p>Gecersiz strateji kodu gonderildiginde bos sonuc listesi doner (200 OK).</p>
     *
     * @param strategy strateji kodu (orn. "rsi_oversold", "macd_positive")
     * @return tarama sonucu (strateji bilgileri + eslesen hisse listesi)
     */
    @GetMapping("/scan")
    public ResponseEntity<HazirTaramalarResponseDto> scan(
            @RequestParam("strategy") String strategy) {
        if (strategy == null || strategy.isBlank() || strategy.length() > 50) {
            log.warn("[HAZIR-TARAMALAR-CTRL] Gecersiz strateji parametresi: {}",
                    strategy != null ? strategy.replaceAll("[\\r\\n]", "_") : "null");
            return ResponseEntity.badRequest().build();
        }

        HazirTaramalarResponseDto result = hazirTaramalarService.scan(strategy);
        return ResponseEntity.ok(result);
    }
}
