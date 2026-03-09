package com.scyborsa.api.controller;

import com.scyborsa.api.dto.fund.FundDetailDto;
import com.scyborsa.api.dto.fund.FundDto;
import com.scyborsa.api.dto.fund.FundStatsDto;
import com.scyborsa.api.dto.fund.FundTimeSeriesDto;
import com.scyborsa.api.service.FundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * TEFAS fon verileri REST controller'i.
 *
 * <p>Fon listesi, arama, populer fonlar ve istatistik endpoint'lerini sunar.
 * Tum veriler api.velzon.tr uzerindeki TEFAS Fund API'den gelir.</p>
 *
 * @see FundService
 * @see FundDto
 * @see FundStatsDto
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/funds")
@RequiredArgsConstructor
public class FundController {

    private final FundService fundService;

    /**
     * Tum aktif fonlarin listesini dondurur.
     *
     * @return fon listesi (JSON array)
     */
    @GetMapping
    public ResponseEntity<List<FundDto>> getAllFunds() {
        log.info("[FUND-API] Tum fonlar isteniyor");
        List<FundDto> funds = fundService.getAllFunds();
        return ResponseEntity.ok(funds);
    }

    /**
     * Fon arama endpoint'i.
     *
     * <p>Arama terimi en az 2, en fazla 50 karakter olmalidir.
     * Log injection onlenmesi icin CR/LF karakterleri temizlenir.</p>
     *
     * @param query arama terimi (min 2 karakter)
     * @param limit maksimum sonuc sayisi (varsayilan: 20)
     * @return eslesen fon listesi
     */
    @GetMapping("/search")
    public ResponseEntity<List<FundDto>> searchFunds(
            @RequestParam String query,
            @RequestParam(defaultValue = "20") int limit) {

        // Input validation
        if (query == null || query.trim().length() < 2 || query.trim().length() > 50) {
            return ResponseEntity.badRequest().body(List.of());
        }
        if (limit < 1 || limit > 100) {
            return ResponseEntity.badRequest().body(List.of());
        }

        // Log sanitization — CR/LF temizle
        String sanitized = query.replaceAll("[\\r\\n]", "").trim();
        log.info("[FUND-API] Fon araniyor [query={}, limit={}]", sanitized, limit);

        List<FundDto> results = fundService.searchFunds(sanitized, limit);
        return ResponseEntity.ok(results);
    }

    /**
     * Populer fonlarin listesini dondurur.
     *
     * @param limit maksimum sonuc sayisi (varsayilan: 50)
     * @return populer fon listesi
     */
    @GetMapping("/popular")
    public ResponseEntity<List<FundDto>> getPopularFunds(
            @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 100) {
            return ResponseEntity.badRequest().body(List.of());
        }
        log.info("[FUND-API] Populer fonlar isteniyor [limit={}]", limit);
        List<FundDto> funds = fundService.getPopularFunds(limit);
        return ResponseEntity.ok(funds);
    }

    /**
     * Tek fon detay bilgisi döndürür.
     *
     * @param code TEFAS fon kodu (1-10 karakter, alfanümerik)
     * @return Fon bilgisi veya 400/404
     */
    @GetMapping("/{code}")
    public ResponseEntity<FundDto> getFundByCode(@PathVariable String code) {
        if (code == null || code.length() > 10 || !code.matches("[a-zA-Z0-9]+")) {
            return ResponseEntity.badRequest().build();
        }
        String sanitized = code.replaceAll("[\\r\\n]", "").toUpperCase();
        log.info("[FUND-API] Fon detay isteniyor [code={}]", sanitized);

        FundDto fund = fundService.getFundByCode(sanitized);
        if (fund == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(fund);
    }

    /**
     * Fon istatistiklerini dondurur.
     *
     * @return fon istatistikleri (toplam fon, yatirimci, portfoy buyuklugu)
     */
    @GetMapping("/stats")
    public ResponseEntity<FundStatsDto> getFundStats() {
        log.info("[FUND-API] Fon istatistikleri isteniyor");
        FundStatsDto stats = fundService.getFundStats();
        return ResponseEntity.ok(stats);
    }

    /**
     * Fon detay bilgilerini dondurur.
     *
     * <p>Varlik dagilimi, portfoy pozisyonlari, benzer fonlar,
     * kategori siralamalari, sektor agirliklari ve fiyat gecmisi
     * gibi kapsamli bilgileri icerir.</p>
     *
     * @param code TEFAS fon kodu (1-10 karakter, alfanumerik)
     * @return fon detay bilgileri veya 400/404
     */
    @GetMapping("/{code}/detail")
    public ResponseEntity<FundDetailDto> getFundDetail(@PathVariable String code) {
        if (code == null || code.length() > 10 || !code.matches("[a-zA-Z0-9]+")) {
            return ResponseEntity.badRequest().build();
        }
        String sanitized = code.replaceAll("[\\r\\n]", "").toUpperCase();
        log.info("[FUND-API] Fon detay isteniyor [code={}]", sanitized);

        FundDetailDto detail = fundService.getFundDetail(sanitized);
        if (detail == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(detail);
    }

    /**
     * Fon nakit akisi (cashflow) zaman serisi verilerini dondurur.
     *
     * @param code TEFAS fon kodu (1-10 karakter, alfanumerik)
     * @return nakit akisi zaman serisi listesi veya 400
     */
    @GetMapping("/{code}/cashflow")
    public ResponseEntity<List<FundTimeSeriesDto>> getFundCashflow(@PathVariable String code) {
        if (code == null || code.length() > 10 || !code.matches("[a-zA-Z0-9]+")) {
            return ResponseEntity.badRequest().build();
        }
        String sanitized = code.replaceAll("[\\r\\n]", "").toUpperCase();
        log.info("[FUND-API] Fon nakit akisi isteniyor [code={}]", sanitized);

        List<FundTimeSeriesDto> cashflow = fundService.getFundCashflow(sanitized);
        return ResponseEntity.ok(cashflow);
    }

    /**
     * Fon yatirimci sayisi zaman serisi verilerini dondurur.
     *
     * @param code TEFAS fon kodu (1-10 karakter, alfanumerik)
     * @return yatirimci sayisi zaman serisi listesi veya 400
     */
    @GetMapping("/{code}/investors")
    public ResponseEntity<List<FundTimeSeriesDto>> getFundInvestors(@PathVariable String code) {
        if (code == null || code.length() > 10 || !code.matches("[a-zA-Z0-9]+")) {
            return ResponseEntity.badRequest().build();
        }
        String sanitized = code.replaceAll("[\\r\\n]", "").toUpperCase();
        log.info("[FUND-API] Fon yatirimci verisi isteniyor [code={}]", sanitized);

        List<FundTimeSeriesDto> investors = fundService.getFundInvestors(sanitized);
        return ResponseEntity.ok(investors);
    }

    /**
     * Fon PDF raporunu indirir.
     *
     * <p>Dosya adi {@code {CODE}_report.pdf} formatinda doner.
     * Content-Type: application/pdf.</p>
     *
     * @param code TEFAS fon kodu (1-10 karakter, alfanumerik)
     * @return PDF byte dizisi veya 400/404
     */
    @GetMapping("/{code}/pdf")
    public ResponseEntity<byte[]> getFundPdf(@PathVariable String code) {
        if (code == null || code.length() > 10 || !code.matches("[a-zA-Z0-9]+")) {
            return ResponseEntity.badRequest().build();
        }
        String sanitized = code.replaceAll("[\\r\\n]", "").toUpperCase();
        log.info("[FUND-API] Fon PDF raporu isteniyor [code={}]", sanitized);

        byte[] pdf = fundService.getFundPdf(sanitized);
        if (pdf == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + sanitized + "_report.pdf")
                .body(pdf);
    }

    /**
     * Fon fiyat gecmisini CSV olarak indirir.
     *
     * <p>Dosya adi {@code {CODE}_history.csv} formatinda doner.
     * Content-Type: text/csv.</p>
     *
     * @param code TEFAS fon kodu (1-10 karakter, alfanumerik)
     * @return CSV byte dizisi veya 400/404
     */
    @GetMapping("/{code}/history/csv")
    public ResponseEntity<byte[]> getFundCsv(@PathVariable String code) {
        if (code == null || code.length() > 10 || !code.matches("[a-zA-Z0-9]+")) {
            return ResponseEntity.badRequest().build();
        }
        String sanitized = code.replaceAll("[\\r\\n]", "").toUpperCase();
        log.info("[FUND-API] Fon CSV gecmisi isteniyor [code={}]", sanitized);

        byte[] csv = fundService.getFundCsv(sanitized);
        if (csv == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + sanitized + "_history.csv")
                .body(csv);
    }
}
