package com.scyborsa.api.controller;

import com.scyborsa.api.dto.enrichment.OrderbookResponseDto;
import com.scyborsa.api.service.enrichment.OrderbookService;
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
 * Emir defteri (orderbook) REST controller'ı.
 *
 * <p>Hisse bazlı emir defteri işlem verilerini sunan endpoint.
 * {@code /api/v1/stock/{stockCode}/orderbook} yolunda çalışır.</p>
 *
 * @see OrderbookService
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/stock")
@RequiredArgsConstructor
public class OrderbookController {

    private final OrderbookService orderbookService;

    /**
     * Hisse bazlı emir defteri işlemlerini döner.
     *
     * @param stockCode hisse kodu (ör: "GARAN"), 2-6 karakter alfanümerik
     * @return zenginleştirilmiş orderbook response
     */
    @GetMapping("/{stockCode}/orderbook")
    public ResponseEntity<OrderbookResponseDto> getOrderbook(
            @PathVariable @Pattern(regexp = "^[A-Z0-9]{2,6}$", message = "Geçersiz hisse kodu") String stockCode) {
        log.info("[Orderbook] İstek alındı: stockCode={}", stockCode);
        return ResponseEntity.ok(orderbookService.getOrderbookTransactions(stockCode));
    }
}
