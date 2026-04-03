package com.scyborsa.api.controller;

import com.scyborsa.api.dto.fintables.SektorelKarsilastirmaDto;
import com.scyborsa.api.service.enrichment.SektorelKarsilastirmaService;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Sektörel oran karşılaştırma REST controller'ı.
 *
 * <p>Bir hissenin finansal oranlarını sektör ortalaması ve medyanı
 * ile karşılaştıran endpoint'leri sağlar.</p>
 *
 * <p><b>Base path:</b> {@code /api/v1/sektorel-karsilastirma}</p>
 *
 * @see SektorelKarsilastirmaService
 * @see SektorelKarsilastirmaDto
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/sektorel-karsilastirma")
@RequiredArgsConstructor
public class SektorelKarsilastirmaController {

    private final SektorelKarsilastirmaService sektorelKarsilastirmaService;

    /**
     * Belirtilen hisse için sektörel oran karşılaştırmasını döndürür.
     *
     * <p>Şirketin F/K, PD/DD, ROE gibi oranlarını aynı sektördeki
     * diğer hisselerle karşılaştırarak sektörel konumunu belirler.</p>
     *
     * @param stockCode hisse kodu (ör: "GARAN"), 1-10 karakter büyük harf/rakam
     * @return {@link ResponseEntity} içinde {@link SektorelKarsilastirmaDto} — şirket oranları, sektör ortalaması/medyanı ve pozisyon bilgisi
     */
    @GetMapping("/{stockCode}")
    public ResponseEntity<SektorelKarsilastirmaDto> getKarsilastirma(
            @PathVariable @Pattern(regexp = "^[A-Z0-9]{1,10}$",
                    message = "Hisse kodu 1-10 karakter, büyük harf ve rakam olmalıdır")
            String stockCode) {
        log.debug("[SEKTOREL-KARSILASTIRMA] Karşılaştırma isteği: stockCode={}", stockCode);
        SektorelKarsilastirmaDto result = sektorelKarsilastirmaService.getSektorelKarsilastirma(stockCode);
        return ResponseEntity.ok(result);
    }
}
