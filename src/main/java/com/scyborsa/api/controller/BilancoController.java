package com.scyborsa.api.controller;

import com.scyborsa.api.dto.bilanco.BilancoDataDto;
import com.scyborsa.api.dto.bilanco.RasyoDetayDto;
import com.scyborsa.api.dto.bilanco.SonBilancoRaporDto;
import com.scyborsa.api.service.bilanco.BilancoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bilanco REST controller.
 *
 * <p>Gate Velzon API'sinden alinan bilanco, gelir tablosu, nakit akim ve rasyo
 * verilerini {@code /api/v1/bilanco} prefix'i altinda sunar.</p>
 *
 * <p>Tum endpoint'ler {@code Map<String, Object>} doner ve null degerler
 * icin graceful handling yapar.</p>
 *
 * @see BilancoService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/bilanco")
@RequiredArgsConstructor
public class BilancoController {

    private final BilancoService bilancoService;

    /**
     * Sembol parametresini dogruler ve normalize eder.
     *
     * @param symbol ham sembol degeri
     * @return normalize edilmis sembol; gecersizse null
     */
    private String validateSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        String upper = symbol.toUpperCase(Locale.ROOT);
        if (!upper.matches("^[A-Z0-9]{1,20}$")) return null;
        return upper;
    }

    /**
     * Tum hisselerin son bilanco rapor bilgilerini dondurur.
     *
     * @return son rapor listesi ({@code data}) ve toplam sayi ({@code totalCount})
     */
    @GetMapping("/son")
    public Map<String, Object> getSonRaporlar() {
        log.info("[BILANCO] Son raporlar listesi istendi");
        List<SonBilancoRaporDto> raporlar = bilancoService.getSonRaporlar();
        return Map.of(
                "data", raporlar,
                "totalCount", raporlar.size()
        );
    }

    /**
     * Belirtilen sembolun son bilanco rapor bilgisini dondurur.
     *
     * @param symbol hisse sembolu (orn. "GARAN")
     * @return son rapor bilgisi ({@code data}), bulunamazsa null data ile
     */
    @GetMapping("/son/{symbol}")
    public Map<String, Object> getSonRapor(@PathVariable String symbol) {
        String safe = validateSymbol(symbol);
        if (safe == null) return Map.of("error", "Gecersiz sembol");
        log.info("[BILANCO] Son rapor istendi: {}", safe);
        SonBilancoRaporDto rapor = bilancoService.getSonRapor(safe);
        Map<String, Object> result = new HashMap<>();
        result.put("data", rapor);
        result.put("found", rapor != null);
        return result;
    }

    /**
     * Belirtilen sembolun bilancosunu dondurur.
     *
     * @param symbol hisse sembolu (orn. "GARAN")
     * @return bilanco verisi ({@code data}), bulunamazsa null data ile
     */
    @GetMapping("/{symbol}")
    public Map<String, Object> getBilanco(@PathVariable String symbol) {
        String safe = validateSymbol(symbol);
        if (safe == null) return Map.of("error", "Gecersiz sembol");
        log.info("[BILANCO] Bilanco istendi: {}", safe);
        BilancoDataDto bilanco = bilancoService.getBilanco(safe);
        Map<String, Object> result = new HashMap<>();
        result.put("data", bilanco);
        result.put("found", bilanco != null);
        return result;
    }

    /**
     * Belirtilen sembolun gelir tablosunu dondurur.
     *
     * @param symbol hisse sembolu (orn. "GARAN")
     * @return gelir tablosu verisi ({@code data}), bulunamazsa null data ile
     */
    @GetMapping("/{symbol}/income")
    public Map<String, Object> getGelirTablosu(@PathVariable String symbol) {
        String safe = validateSymbol(symbol);
        if (safe == null) return Map.of("error", "Gecersiz sembol");
        log.info("[BILANCO] Gelir tablosu istendi: {}", safe);
        BilancoDataDto gelirTablosu = bilancoService.getGelirTablosu(safe);
        Map<String, Object> result = new HashMap<>();
        result.put("data", gelirTablosu);
        result.put("found", gelirTablosu != null);
        return result;
    }

    /**
     * Belirtilen sembolun nakit akim tablosunu dondurur.
     *
     * @param symbol hisse sembolu (orn. "GARAN")
     * @return nakit akim verisi ({@code data}), bulunamazsa null data ile
     */
    @GetMapping("/{symbol}/cashflow")
    public Map<String, Object> getNakitAkim(@PathVariable String symbol) {
        String safe = validateSymbol(symbol);
        if (safe == null) return Map.of("error", "Gecersiz sembol");
        log.info("[BILANCO] Nakit akim tablosu istendi: {}", safe);
        BilancoDataDto nakitAkim = bilancoService.getNakitAkim(safe);
        Map<String, Object> result = new HashMap<>();
        result.put("data", nakitAkim);
        result.put("found", nakitAkim != null);
        return result;
    }

    /**
     * Belirtilen sembolun tum finansal raporlarini dondurur
     * (bilanco, gelir tablosu, nakit akim).
     *
     * @param symbol hisse sembolu (orn. "GARAN")
     * @return rapor map'i ({@code data}: balanceSheet/incomeStatement/cashFlowStatement keyleri)
     */
    @GetMapping("/{symbol}/all")
    public Map<String, Object> getAllReports(@PathVariable String symbol) {
        String safe = validateSymbol(symbol);
        if (safe == null) return Map.of("error", "Gecersiz sembol");
        log.info("[BILANCO] Tum raporlar istendi: {}", safe);
        Map<String, BilancoDataDto> reports = bilancoService.getAllReports(safe);
        Map<String, Object> result = new HashMap<>();
        result.put("data", reports);
        result.put("reportCount", reports.size());
        return result;
    }

    /**
     * Belirtilen sembolun finansal rasyolarini (oranlarini) dondurur.
     *
     * @param symbol hisse sembolu (orn. "GARAN")
     * @return rasyo detaylari ({@code data}), bulunamazsa null data ile
     */
    @GetMapping("/{symbol}/rasyo")
    public Map<String, Object> getRasyo(@PathVariable String symbol) {
        String safe = validateSymbol(symbol);
        if (safe == null) return Map.of("error", "Gecersiz sembol");
        log.info("[BILANCO] Rasyo istendi: {}", safe);
        RasyoDetayDto rasyo = bilancoService.getRasyo(safe);
        Map<String, Object> result = new HashMap<>();
        result.put("data", rasyo);
        result.put("found", rasyo != null);
        return result;
    }
}
