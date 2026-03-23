package com.scyborsa.api.controller;

import com.scyborsa.api.dto.market.RegressionChannelDto;
import com.scyborsa.api.service.market.RegressionScreenerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Regresyon kanali tarama REST controller.
 *
 * <p>Django indicator screener API'sinden alinan regresyon kanali verilerini
 * {@code /api/v1/regression-screener} prefix'i altinda sunar.</p>
 *
 * @see com.scyborsa.api.service.market.RegressionScreenerService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/regression-screener")
@RequiredArgsConstructor
public class RegressionScreenerController {

    private final RegressionScreenerService regressionScreenerService;

    /**
     * Regresyon kanali tarama sonuclarini dondurur.
     *
     * @return regresyon listesi ({@code regressions}) ve toplam sayi ({@code totalCount})
     */
    @GetMapping("/scan")
    public Map<String, Object> scan() {
        log.info("[REGRESSION-SCREENER] Regresyon kanali tarama istendi");
        List<RegressionChannelDto> regressions = regressionScreenerService.getRegressions();
        return Map.of(
                "regressions", regressions,
                "totalCount", regressions.size()
        );
    }
}
