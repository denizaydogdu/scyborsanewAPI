package com.scyborsa.api.controller;

import com.scyborsa.api.service.market.CandlePatternService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Mum formasyonu (candlestick pattern) tarama REST controller'i.
 *
 * <p>Belirtilen periyot icin mum formasyonu tarama sonuclarini sunar.
 * Periyot parametresi sanitize edilir (maks 3 karakter, alfanumerik).</p>
 *
 * @see com.scyborsa.api.service.market.CandlePatternService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/candle-patterns")
@RequiredArgsConstructor
public class CandlePatternController {

    private final CandlePatternService candlePatternService;

    /**
     * Mum formasyonu taramasi yapar.
     *
     * <p>Periyot parametresi maks 3 karakter ile sinirlandirilir ve
     * alfanumerik olmayan karakterler temizlenir.</p>
     *
     * @param period tarama periyodu (varsayilan: 1D; gecerli degerler: 15, 1H, 4H, 1D, 1W)
     * @return tarama sonuclari: stocks listesi, totalCount ve period
     */
    @GetMapping("/scan")
    public Map<String, Object> scan(@RequestParam(defaultValue = "1D") String period) {
        // Sanitize: max 3 karakter, alfanumerik olmayan karakterleri temizle
        String sanitized = period.replaceAll("[^a-zA-Z0-9]", "");
        sanitized = sanitized.length() > 3 ? sanitized.substring(0, 3) : sanitized;

        log.info("[CANDLE-PATTERN] Tarama istegi: period={}", sanitized);
        return candlePatternService.getPatterns(sanitized);
    }
}
