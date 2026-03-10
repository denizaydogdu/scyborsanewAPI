package com.scyborsa.api.controller;

import com.scyborsa.api.dto.enrichment.TakasResponseDto;
import com.scyborsa.api.service.enrichment.TakasService;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Takas (Saklama Dağılımı) REST controller'ı.
 * Hisse bazlı saklama kuruluşu dağılım verilerini sunar.
 */
@Validated
@Slf4j
@RestController
@RequestMapping("/api/v1/stock")
@RequiredArgsConstructor
public class TakasController {

    private final TakasService takasService;

    /**
     * Hisse bazlı Takas (saklama dağılımı) verilerini getirir.
     *
     * <p>Fintables API'den bugünün verisini çeker, DB'deki aracı kurum
     * bilgileri ile zenginleştirir ve döndürür.</p>
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return Takas dağılımı (saklama kuruluşları listesi)
     */
    @GetMapping("/{stockCode}/takas")
    public ResponseEntity<TakasResponseDto> getTakas(
            @PathVariable @Pattern(regexp = "^[A-Z0-9]{2,6}$", message = "Geçersiz hisse kodu") String stockCode) {
        TakasResponseDto response = takasService.getTakasDistribution(stockCode);
        return ResponseEntity.ok(response);
    }
}
