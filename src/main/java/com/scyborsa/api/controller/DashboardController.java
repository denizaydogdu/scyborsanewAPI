package com.scyborsa.api.controller;

import com.scyborsa.api.dto.DashboardSentimentDto;
import com.scyborsa.api.dto.IndexPerformanceDto;
import com.scyborsa.api.service.IndexPerformanceService;
import com.scyborsa.api.service.SentimentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Dashboard verilerini sunan REST controller.
 *
 * <p>Piyasa sentiment (duyarlilik) verisini ve endeks performans
 * verilerini saglar.</p>
 *
 * <p>Temel endpoint: {@code /api/v1/dashboard}</p>
 *
 * @see SentimentService
 * @see IndexPerformanceService
 * @see DashboardSentimentDto
 * @see IndexPerformanceDto
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final SentimentService sentimentService;
    private final IndexPerformanceService indexPerformanceService;

    /**
     * Piyasa sentiment verisini dondurur.
     *
     * <p>HTTP GET {@code /api/v1/dashboard/sentiment}</p>
     *
     * <p>BIST hisselerinin kisa, orta ve uzun vadeli yukselis oranlarini
     * yuzde olarak hesaplayip dondurur. Hata durumunda tum degerler
     * 0.0 olarak doner (graceful degradation).</p>
     *
     * @return sentiment verisini iceren {@link DashboardSentimentDto}
     */
    @GetMapping("/sentiment")
    public ResponseEntity<DashboardSentimentDto> getSentiment() {
        DashboardSentimentDto sentiment = sentimentService.getSentimentData();
        return ResponseEntity.ok(sentiment);
    }

    /**
     * BIST endeks performans verilerini dondurur.
     *
     * <p>HTTP GET {@code /api/v1/dashboard/indexes}</p>
     *
     * <p>Tum BIST endekslerinin (XU100, XBANK, vb.) gunluk, haftalik, aylik,
     * ceyreklik, 6 aylik ve yillik performans degisim yuzdeleri ile
     * son fiyat bilgisini dondurur. Veriler volatile cache ile 60 saniye
     * saklanir.</p>
     *
     * @return endeks performans listesini iceren {@link IndexPerformanceDto} listesi;
     *         hata durumunda bos liste
     */
    @GetMapping("/indexes")
    public ResponseEntity<List<IndexPerformanceDto>> getIndexes() {
        return ResponseEntity.ok(indexPerformanceService.getIndexPerformances());
    }
}
