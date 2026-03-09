package com.scyborsa.api.controller;

import com.scyborsa.api.dto.analyst.AnalystRatingDto;
import com.scyborsa.api.service.AnalystRatingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Analist tavsiye REST controller.
 *
 * <p>Fintables API'den gelen analist tavsiyelerini sunar.</p>
 *
 * @see AnalystRatingService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/analyst-ratings")
@RequiredArgsConstructor
public class AnalystRatingController {

    private final AnalystRatingService analystRatingService;

    /**
     * Tum analist tavsiyelerini dondurur.
     *
     * @return analist tavsiye listesi
     */
    @GetMapping
    public ResponseEntity<List<AnalystRatingDto>> getAnalystRatings() {
        log.debug("[ANALYST-RATING] Analist tavsiyeleri istendi");
        List<AnalystRatingDto> ratings = analystRatingService.getAnalystRatings();
        return ResponseEntity.ok(ratings);
    }

    /**
     * Belirli bir hisse koduna ait analist tavsiyelerini dondurur.
     *
     * <p>Cache'teki tum tavsiyeleri filtreler. Hisse kodu buyuk harfe donusturulur.
     * Eslesen tavsiye yoksa bos liste ile 200 OK dondurur.</p>
     *
     * @param stockCode hisse kodu (BIST ticker, ornek: PETKM)
     * @return eslesen analist tavsiye listesi
     */
    @GetMapping("/{stockCode}")
    public ResponseEntity<List<AnalystRatingDto>> getByStockCode(@PathVariable String stockCode) {
        if (stockCode == null || stockCode.isBlank()) {
            log.warn("[ANALYST-RATING] Gecersiz stockCode: bos veya null");
            return ResponseEntity.badRequest().build();
        }

        String code = stockCode.trim().toUpperCase();

        if (code.length() > 10 || !code.matches("[A-Z0-9]+")) {
            log.warn("[ANALYST-RATING] Gecersiz stockCode formati: {}", code);
            return ResponseEntity.badRequest().build();
        }

        log.debug("[ANALYST-RATING] Hisse kodu ile tavsiye istendi: {}", code);
        List<AnalystRatingDto> filtered = analystRatingService.getAnalystRatings().stream()
                .filter(r -> code.equals(r.getStockCode()))
                .collect(Collectors.toList());

        log.debug("[ANALYST-RATING] {} icin {} tavsiye bulundu", code, filtered.size());
        return ResponseEntity.ok(filtered);
    }
}
