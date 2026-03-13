package com.scyborsa.api.controller;

import com.scyborsa.api.dto.enrichment.AkdResponseDto;
import com.scyborsa.api.service.enrichment.AkdService;
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
 * AKD (Aracı Kurum Dağılımı) REST controller'ı.
 * Hisse bazlı aracı kurum dağılım verilerini sunar.
 *
 * @see AkdService
 */
@Validated
@Slf4j
@RestController
@RequestMapping("/api/v1/stock")
@RequiredArgsConstructor
public class AkdController {

    private final AkdService akdService;

    /**
     * Hisse bazlı AKD (Aracı Kurum Dağılımı) verilerini getirir.
     * Fintables API'den bugünün verisini çeker, DB'deki aracı kurum bilgileri ile
     * zenginleştirir ve alıcı/satıcı/toplam olarak döndürür.
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return AKD dağılımı (alıcılar, satıcılar, toplam listeleri)
     */
    @GetMapping("/{stockCode}/akd")
    public ResponseEntity<AkdResponseDto> getAkd(
            @PathVariable @Pattern(regexp = "^[A-Z0-9]{2,6}$", message = "Geçersiz hisse kodu") String stockCode) {
        AkdResponseDto response = akdService.getAkdDistribution(stockCode);
        return ResponseEntity.ok(response);
    }
}
