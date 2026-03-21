package com.scyborsa.api.controller;

import com.scyborsa.api.dto.market.DividendDto;
import com.scyborsa.api.service.market.DividendService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Temettu (dividend) REST controller.
 *
 * <p>Velzon API'sinden alinan temettu verilerini
 * {@code /api/v1/dividends} prefix'i altinda sunar.</p>
 *
 * @see com.scyborsa.api.service.market.DividendService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/dividends")
@RequiredArgsConstructor
public class DividendController {

    private final DividendService dividendService;

    /**
     * Temettu listesini dondurur.
     *
     * @return temettu listesi ({@code dividends}) ve toplam sayi ({@code totalCount})
     */
    @GetMapping
    public Map<String, Object> getDividends() {
        log.info("[TEMETTÜ] Temettu listesi istendi");
        List<DividendDto> dividends = dividendService.getDividends();
        return Map.of(
                "dividends", dividends,
                "totalCount", dividends.size()
        );
    }
}
