package com.scyborsa.api.controller;

import com.scyborsa.api.service.ai.VelzonAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AI degerlendirme REST controller'i.
 *
 * <p>Velzon AI API uzerinden hisse teknik analiz yorumu saglar.
 * {@code /api/v1/ai} prefix'i altinda endpoint'ler sunar.</p>
 *
 * <p>Hisse kodu validasyonu: {@code ^[A-Z0-9]{2,6}$} regex
 * (A1CAP gibi alfanumerik kodlari destekler).</p>
 *
 * @see VelzonAiService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiController {

    /** Velzon AI analiz servisi. */
    private final VelzonAiService velzonAiService;

    /**
     * Belirtilen hisse icin AI teknik analiz yorumu dondurur.
     *
     * <p>Basarili durumda stockCode ve comment alanlarini icerir.
     * AI devre disiysa HTTP 503, yorum alinamazsa HTTP 502 dondurur.</p>
     *
     * @param stockCode hisse kodu (2-6 karakter, buyuk harf + rakam)
     * @return AI yorumu veya hata mesaji
     */
    @GetMapping("/comment/{stockCode}")
    public ResponseEntity<?> getAiComment(@PathVariable String stockCode) {
        // Validation: regex ^[A-Z0-9]{2,6}$
        if (stockCode == null || !stockCode.matches("^[A-Z0-9]{2,6}$")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Gecersiz hisse kodu. 2-6 karakter, buyuk harf ve rakam."));
        }

        if (!velzonAiService.isEnabled()) {
            return ResponseEntity.status(503)
                    .body(Map.of("error", "AI servisi su anda devre disi."));
        }

        try {
            String comment = velzonAiService.analyzeStock(stockCode, null, null, List.of());
            if (comment != null && !comment.isBlank()) {
                return ResponseEntity.ok(Map.of(
                        "stockCode", stockCode,
                        "comment", comment
                ));
            }
            return ResponseEntity.status(502)
                    .body(Map.of("error", "AI yorumu alinamadi."));
        } catch (Exception e) {
            log.error("[AI-CONTROLLER] AI yorum hatasi ({}): {}", stockCode, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Sunucu hatasi."));
        }
    }
}
