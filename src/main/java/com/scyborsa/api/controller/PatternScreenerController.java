package com.scyborsa.api.controller;

import com.scyborsa.api.dto.market.PatternFormationDto;
import com.scyborsa.api.service.market.PatternScreenerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Formasyon tarama REST controller.
 *
 * <p>Django pattern screener API'sinden alinan formasyon verilerini
 * {@code /api/v1/pattern-screener} prefix'i altinda sunar.</p>
 *
 * @see com.scyborsa.api.service.market.PatternScreenerService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/pattern-screener")
@RequiredArgsConstructor
public class PatternScreenerController {

    private final PatternScreenerService patternScreenerService;

    /**
     * Formasyon tarama sonuclarini dondurur.
     *
     * @return formasyon listesi ({@code patterns}) ve toplam sayi ({@code totalCount})
     */
    @GetMapping("/scan")
    public Map<String, Object> scan() {
        log.info("[PATTERN-SCREENER] Formasyon tarama istendi");
        List<PatternFormationDto> patterns = patternScreenerService.getPatterns();
        return Map.of(
                "patterns", patterns,
                "totalCount", patterns.size()
        );
    }
}
