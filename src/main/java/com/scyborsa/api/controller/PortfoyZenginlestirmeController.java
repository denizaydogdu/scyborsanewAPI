package com.scyborsa.api.controller;

import com.scyborsa.api.dto.fintables.PortfoyTemelVeriDto;
import com.scyborsa.api.service.enrichment.PortfoyZenginlestirmeService;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Portföy temel veri zenginleştirme REST controller'ı.
 *
 * <p>Hisse bazlı veya toplu portföy temel verisi (F/K, PD/DD, ROE,
 * temettü, analist konsensüs, sinyal sayısı) sorgulama endpoint'leri sağlar.</p>
 *
 * <p><b>Base path:</b> {@code /api/v1/portfoy-zenginlestirme}</p>
 *
 * @see PortfoyZenginlestirmeService
 * @see PortfoyTemelVeriDto
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/portfoy-zenginlestirme")
@RequiredArgsConstructor
public class PortfoyZenginlestirmeController {

    private final PortfoyZenginlestirmeService portfoyZenginlestirmeService;

    /**
     * Belirtilen hisse için portföy temel verilerini döndürür.
     *
     * @param stockCode hisse kodu (ör: "GARAN"), 1-10 karakter büyük harf/rakam
     * @return {@link ResponseEntity} içinde {@link PortfoyTemelVeriDto}
     */
    @GetMapping("/{stockCode}")
    public ResponseEntity<PortfoyTemelVeriDto> getTemelVeri(
            @PathVariable @Pattern(regexp = "^[A-Z0-9]{1,10}$",
                    message = "Hisse kodu 1-10 karakter, büyük harf ve rakam olmalıdır")
            String stockCode) {
        log.debug("[PORTFOY-ZENGIN] Tek hisse isteği: stockCode={}", stockCode);
        PortfoyTemelVeriDto result = portfoyZenginlestirmeService.zenginlestir(stockCode);
        return ResponseEntity.ok(result);
    }

    /**
     * Birden fazla hisse için toplu portföy temel verilerini döndürür.
     *
     * @param stockCodes hisse kodu listesi (ör: ["GARAN", "THYAO"])
     * @return {@link ResponseEntity} içinde {@link PortfoyTemelVeriDto} listesi
     */
    @PostMapping("/toplu")
    public ResponseEntity<List<PortfoyTemelVeriDto>> getTopluTemelVeri(
            @RequestBody List<String> stockCodes) {
        log.debug("[PORTFOY-ZENGIN] Toplu istek: {} hisse", stockCodes != null ? stockCodes.size() : 0);
        List<PortfoyTemelVeriDto> result = portfoyZenginlestirmeService.zenginlestirToplu(stockCodes);
        return ResponseEntity.ok(result);
    }
}
