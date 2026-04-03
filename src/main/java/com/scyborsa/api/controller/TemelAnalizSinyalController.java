package com.scyborsa.api.controller;

import com.scyborsa.api.dto.fintables.TemelAnalizSinyalDto;
import com.scyborsa.api.service.enrichment.TemelAnalizSinyalService;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Temel analiz sinyal REST controller'ı.
 *
 * <p>Bir hissenin temel analiz verilerinden türetilen sinyalleri
 * (düşük F/K, güçlü ROE, iflas riski vb.) döndüren endpoint'leri sağlar.</p>
 *
 * <p><b>Base path:</b> {@code /api/v1/temel-sinyal}</p>
 *
 * @see TemelAnalizSinyalService
 * @see TemelAnalizSinyalDto
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/temel-sinyal")
@RequiredArgsConstructor
public class TemelAnalizSinyalController {

    private final TemelAnalizSinyalService temelAnalizSinyalService;

    /**
     * Belirtilen hisse için temel analiz sinyallerini döndürür.
     *
     * <p>Her sinyal kuralı bağımsız çalışır. Bir kural başarısız olursa
     * diğerleri yine sinyal üretmeye devam eder.</p>
     *
     * @param stockCode hisse kodu (ör: "GARAN"), 1-10 karakter büyük harf/rakam
     * @return {@link ResponseEntity} içinde {@link TemelAnalizSinyalDto} listesi (boş olabilir)
     */
    @GetMapping("/{stockCode}")
    public ResponseEntity<List<TemelAnalizSinyalDto>> getSinyaller(
            @PathVariable @Pattern(regexp = "^[A-Z0-9]{1,10}$",
                    message = "Hisse kodu 1-10 karakter, büyük harf ve rakam olmalıdır")
            String stockCode) {
        log.debug("[TEMEL-SINYAL] Sinyal isteği: stockCode={}", stockCode);
        List<TemelAnalizSinyalDto> result = temelAnalizSinyalService.getSinyaller(stockCode);
        return ResponseEntity.ok(result);
    }
}
