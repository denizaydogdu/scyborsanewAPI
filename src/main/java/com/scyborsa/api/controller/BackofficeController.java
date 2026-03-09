package com.scyborsa.api.controller;

import com.scyborsa.api.dto.backoffice.BackofficeDashboardDto;
import com.scyborsa.api.dto.backoffice.ScreenerResultSummaryDto;
import com.scyborsa.api.dto.backoffice.StockDto;
import com.scyborsa.api.service.BackofficeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Backoffice REST controller'i.
 *
 * <p>Yonetim paneli dashboard, hisse yonetimi ve tarama izleme
 * endpoint'lerini sunar. Tum endpoint'ler {@code /api/v1/backoffice}
 * prefix'i altindadir.</p>
 *
 * @see BackofficeService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/backoffice")
@RequiredArgsConstructor
public class BackofficeController {

    private final BackofficeService backofficeService;

    /**
     * Dashboard KPI verilerini dondurur.
     *
     * <p>HTTP GET {@code /api/v1/backoffice/dashboard}</p>
     *
     * @return dashboard istatistikleri
     */
    @GetMapping("/dashboard")
    public ResponseEntity<BackofficeDashboardDto> getDashboard() {
        BackofficeDashboardDto dashboard = backofficeService.getDashboard();
        return ResponseEntity.ok(dashboard);
    }

    /**
     * Hisse listesini filtreye gore getirir.
     *
     * <p>HTTP GET {@code /api/v1/backoffice/stocks?filtre=all}</p>
     *
     * @param filtre "all", "aktif" veya "yasakli" (varsayilan: "all")
     * @return filtrelenmis hisse listesi
     */
    @GetMapping("/stocks")
    public ResponseEntity<List<StockDto>> getStocks(
            @RequestParam(defaultValue = "all") String filtre) {
        List<StockDto> stocks = backofficeService.getStocks(filtre);
        return ResponseEntity.ok(stocks);
    }

    /**
     * Hisseyi yasaklar.
     *
     * <p>HTTP POST {@code /api/v1/backoffice/stocks/{stockName}/yasak}</p>
     *
     * @param stockName yasaklanacak hisse kodu
     * @param body request body — "neden" alani icermeli
     * @return HTTP 200
     */
    @PostMapping("/stocks/{stockName}/yasak")
    public ResponseEntity<Void> yasakla(
            @PathVariable String stockName,
            @RequestBody Map<String, String> body) {
        String neden = body.getOrDefault("neden", "Belirtilmedi");
        log.info("[BACKOFFICE] Hisse yasaklama istegi: {} — neden: {}", stockName, neden);
        backofficeService.yasakla(stockName, neden);
        return ResponseEntity.ok().build();
    }

    /**
     * Hissenin yasagini kaldirir.
     *
     * <p>HTTP DELETE {@code /api/v1/backoffice/stocks/{stockName}/yasak}</p>
     *
     * @param stockName yasagi kaldirilacak hisse kodu
     * @return HTTP 200
     */
    @DeleteMapping("/stocks/{stockName}/yasak")
    public ResponseEntity<Void> yasakKaldir(@PathVariable String stockName) {
        log.info("[BACKOFFICE] Hisse yasak kaldirma istegi: {}", stockName);
        backofficeService.yasakKaldir(stockName);
        return ResponseEntity.ok().build();
    }

    /**
     * Bugunun tarama sonuclarini getirir.
     *
     * <p>HTTP GET {@code /api/v1/backoffice/screener/today}</p>
     *
     * @return bugunun tarama sonuclari
     */
    @GetMapping("/screener/today")
    public ResponseEntity<List<ScreenerResultSummaryDto>> getTodayScreenerResults() {
        List<ScreenerResultSummaryDto> results = backofficeService.getTodayScreenerResults();
        return ResponseEntity.ok(results);
    }

    /**
     * {@link IllegalArgumentException} hatalarini HTTP 400 Bad Request olarak doner.
     *
     * @param ex yakalanan istisna
     * @return hata mesaji ile HTTP 400 yaniti
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
