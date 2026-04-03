package com.scyborsa.api.controller;

import com.scyborsa.api.dto.fintables.VbtsTedbirDto;
import com.scyborsa.api.service.enrichment.VbtsTedbirService;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * VBTS tedbirli hisse REST controller'ı.
 *
 * <p>VBTS (Varant, Bono, Tahvil, Sigorta) tedbirli hisselerin listesini,
 * tekil hisse tedbirlerini ve tedbirli olup olmadığını kontrol eden
 * endpoint'leri sağlar.</p>
 *
 * @see VbtsTedbirService
 * @see VbtsTedbirDto
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/vbts")
@RequiredArgsConstructor
public class VbtsTedbirController {

    /** VBTS tedbir servisi. */
    private final VbtsTedbirService vbtsTedbirService;

    /**
     * Tüm aktif VBTS tedbirlerini getirir.
     *
     * @return aktif tedbir listesi
     */
    @GetMapping
    public ResponseEntity<List<VbtsTedbirDto>> getAktifTedbirler() {
        List<VbtsTedbirDto> tedbirler = vbtsTedbirService.getAktifTedbirler();
        return ResponseEntity.ok(tedbirler);
    }

    /**
     * Belirtilen hissenin aktif tedbirlerini getirir.
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return hisseye ait tedbir listesi
     */
    @GetMapping("/{stockCode}")
    public ResponseEntity<List<VbtsTedbirDto>> getHisseTedbirleri(
            @PathVariable @Pattern(regexp = "^[A-Z0-9]{2,6}$", message = "Geçersiz hisse kodu") String stockCode) {
        List<VbtsTedbirDto> tedbirler = vbtsTedbirService.getHisseTedbirleri(stockCode);
        return ResponseEntity.ok(tedbirler);
    }

    /**
     * Belirtilen hissenin tedbirli olup olmadığını kontrol eder.
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return tedbirli ise {@code true}, değilse {@code false}
     */
    @GetMapping("/{stockCode}/check")
    public ResponseEntity<Map<String, Boolean>> checkTedbirli(
            @PathVariable @Pattern(regexp = "^[A-Z0-9]{2,6}$", message = "Geçersiz hisse kodu") String stockCode) {
        boolean tedbirli = vbtsTedbirService.isTedbirli(stockCode);
        return ResponseEntity.ok(Map.of("tedbirli", tedbirli));
    }
}
