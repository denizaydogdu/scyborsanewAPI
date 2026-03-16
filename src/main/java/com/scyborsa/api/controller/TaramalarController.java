package com.scyborsa.api.controller;

import com.scyborsa.api.dto.tarama.TaramalarResponseDto;
import com.scyborsa.api.service.TaramalarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Taramalar sayfası REST controller'ı.
 *
 * <p>Telegram'a gönderilmiş tarama sinyallerini tarih aralığı, tarama adı ve hisse
 * filtrelerine göre sunar. Frontend taramalar sayfası bu endpoint'i tüketir.</p>
 *
 * @see TaramalarService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/taramalar")
@RequiredArgsConstructor
public class TaramalarController {

    private final TaramalarService taramalarService;

    /**
     * Tarama sinyallerini filtreli olarak getirir.
     *
     * <p>Tarih parametreleri verilmezse bugünün verileri döner. Gelecek tarihler
     * bugüne çekilir, ters aralık otomatik düzeltilir.</p>
     *
     * @param startDate başlangıç tarihi (ISO format, opsiyonel — varsayılan bugün)
     * @param endDate bitiş tarihi (ISO format, opsiyonel — varsayılan bugün)
     * @param screener tarama adı filtresi (contains, case-insensitive; opsiyonel)
     * @param stock hisse kodu filtresi (contains, case-insensitive; opsiyonel)
     * @return taramalar listesi, özet istatistikler, filtre dropdown ve toplam kart sayısı
     */
    @GetMapping
    public ResponseEntity<TaramalarResponseDto> getTaramalar(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String screener,
            @RequestParam(required = false) String stock) {

        LocalDate today = LocalDate.now(ZoneId.of("Europe/Istanbul"));

        // Varsayılan bugün
        if (startDate == null) startDate = today;
        if (endDate == null) endDate = today;

        // Parametre uzunluk sınırları
        if (screener != null && screener.length() > 100) screener = screener.substring(0, 100);
        if (stock != null && stock.length() > 20) stock = stock.substring(0, 20);

        // Gelecek tarihleri engelle
        if (startDate.isAfter(today)) {
            log.warn("[TARAMALAR] startDate gelecekte, bugüne çekildi");
            startDate = today;
        }
        if (endDate.isAfter(today)) {
            log.warn("[TARAMALAR] endDate gelecekte, bugüne çekildi");
            endDate = today;
        }

        // Ters aralık düzelt
        if (startDate.isAfter(endDate)) {
            LocalDate temp = startDate;
            startDate = endDate;
            endDate = temp;
        }

        log.info("[TARAMALAR] Taramalar sorgulandı: startDate={}, endDate={}, screener={}, stock={}",
                startDate, endDate, screener, stock);

        return ResponseEntity.ok(taramalarService.getTaramalar(startDate, endDate, screener, stock));
    }
}
