package com.scyborsa.api.controller;

import com.scyborsa.api.dto.fintables.PortfoyOverlayDto;
import com.scyborsa.api.service.enrichment.PortfoyOverlayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Portföy vs BIST100 overlay karşılaştırma REST controller'ı.
 *
 * <p>Kullanıcının portföyündeki hisselerin ortalama temel analiz değerlerini
 * BIST100 ortalaması ile karşılaştıran endpoint sağlar.</p>
 *
 * <p><b>Base path:</b> {@code /api/v1/portfoy-overlay}</p>
 *
 * @see PortfoyOverlayService
 * @see PortfoyOverlayDto
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/portfoy-overlay")
@RequiredArgsConstructor
public class PortfoyOverlayController {

    private final PortfoyOverlayService portfoyOverlayService;

    /**
     * Portföy hisselerinin ortalama temel verilerini BIST100 ile karşılaştırır.
     *
     * @param stockCodes portföydeki hisse kodları listesi (ör: ["GARAN", "THYAO"])
     * @return {@link ResponseEntity} içinde {@link PortfoyOverlayDto} karşılaştırma sonucu
     */
    @PostMapping
    public ResponseEntity<PortfoyOverlayDto> getOverlay(
            @RequestBody List<String> stockCodes) {
        log.debug("[PORTFOY-OVERLAY] Overlay isteği: {} hisse", stockCodes != null ? stockCodes.size() : 0);
        PortfoyOverlayDto result = portfoyOverlayService.hesaplaOverlay(stockCodes);
        return ResponseEntity.ok(result);
    }
}
