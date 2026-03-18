package com.scyborsa.api.controller;

import com.scyborsa.api.enums.EnrichmentDataTypeEnum;
import com.scyborsa.api.model.EnrichmentCache;
import com.scyborsa.api.repository.EnrichmentCacheRepository;
import com.scyborsa.api.service.ai.VelzonAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI degerlendirme REST controller'i.
 *
 * <p>Velzon AI API uzerinden hisse teknik analiz yorumu saglar.
 * {@code /api/v1/ai} prefix'i altinda endpoint'ler sunar.</p>
 *
 * <p>Hisse kodu validasyonu: {@code ^[A-Z0-9]{2,6}$} regex
 * (A1CAP gibi alfanumerik kodlari destekler).</p>
 *
 * <p>AI yanıtları günlük olarak {@link EnrichmentCache} tablosunda
 * {@link EnrichmentDataTypeEnum#AI_COMMENT} tipiyle cache'lenir.
 * Aynı hisse için gün içinde tekrar AI çağrısı yapılmaz.</p>
 *
 * @see VelzonAiService
 * @see EnrichmentCacheRepository
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiController {

    /** Velzon AI analiz servisi. */
    private final VelzonAiService velzonAiService;

    /** Enrichment cache repository. */
    private final EnrichmentCacheRepository enrichmentCacheRepository;

    /**
     * Belirtilen hisse icin AI teknik analiz yorumu dondurur.
     *
     * <p>Önce günlük cache kontrol edilir. Cache hit durumunda kayıtlı yorum
     * döner, miss durumunda VelzonAiService çağrılır ve sonuç cache'e kaydedilir.</p>
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
            LocalDate today = LocalDate.now(ZoneId.of("Europe/Istanbul"));

            // Cache kontrol
            Optional<EnrichmentCache> cached = enrichmentCacheRepository
                    .findByStockCodeAndCacheDateAndDataType(stockCode, today, EnrichmentDataTypeEnum.AI_COMMENT);

            if (cached.isPresent()) {
                log.info("[AI-CONTROLLER] Cache hit: {}", stockCode);
                return ResponseEntity.ok(Map.of(
                        "stockCode", stockCode,
                        "comment", cached.get().getJsonData()
                ));
            }

            // Cache miss — AI çağrısı
            log.info("[AI-CONTROLLER] Cache miss, AI çağrılıyor: {}", stockCode);
            String comment = velzonAiService.analyzeStock(stockCode, null, null, List.of());

            if (comment != null && !comment.isBlank()) {
                // Cache'e kaydet (race condition duplicate yakalama — non-fatal)
                try {
                    EnrichmentCache entry = EnrichmentCache.builder()
                            .stockCode(stockCode)
                            .cacheDate(today)
                            .dataType(EnrichmentDataTypeEnum.AI_COMMENT)
                            .jsonData(comment)
                            .build();
                    enrichmentCacheRepository.save(entry);
                    log.info("[AI-CONTROLLER] Cache saved: {}", stockCode);
                } catch (Exception cacheEx) {
                    log.warn("[AI-CONTROLLER] Cache kayıt hatası (muhtemelen duplicate): {} - {}",
                            stockCode, cacheEx.getMessage());
                }

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
