package com.scyborsa.api.controller;

import com.scyborsa.api.service.enrichment.MlFeatureService;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ML feature engineering REST controller'ı.
 *
 * <p>Belirtilen hisse için makine öğrenmesi modeli feature'larını
 * döndüren endpoint sağlar. Feature'lar temel analiz verilerinden
 * çıkarılır.</p>
 *
 * <p><b>Base path:</b> {@code /api/v1/ml-feature}</p>
 *
 * @see MlFeatureService
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/ml-feature")
@RequiredArgsConstructor
public class MlFeatureController {

    private final MlFeatureService mlFeatureService;

    /**
     * Belirtilen hisse için ML feature'larını döndürür.
     *
     * <p>Dönen map'te yalnızca başarıyla hesaplanan feature'lar bulunur.
     * Hesaplanamayan feature'lar sonuçta yer almaz.</p>
     *
     * @param stockCode hisse kodu (ör: "GARAN"), 1-10 karakter büyük harf/rakam
     * @return {@link ResponseEntity} içinde feature adı → değer map'i
     */
    @GetMapping("/{stockCode}")
    public ResponseEntity<Map<String, Double>> getFeatures(
            @PathVariable @Pattern(regexp = "^[A-Z0-9]{1,10}$",
                    message = "Hisse kodu 1-10 karakter, büyük harf ve rakam olmalıdır")
            String stockCode) {
        log.debug("[ML-FEATURE] Feature isteği: stockCode={}", stockCode);
        Map<String, Double> features = mlFeatureService.extractFeatures(stockCode);
        return ResponseEntity.ok(features);
    }
}
