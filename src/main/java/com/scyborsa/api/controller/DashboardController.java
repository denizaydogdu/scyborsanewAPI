package com.scyborsa.api.controller;

import com.scyborsa.api.dto.market.DashboardSentimentDto;
import com.scyborsa.api.dto.market.GlobalMarketDto;
import com.scyborsa.api.dto.market.IndexPerformanceDto;
import com.scyborsa.api.service.market.GlobalMarketService;
import com.scyborsa.api.service.market.IndexPerformanceService;
import com.scyborsa.api.service.market.SentimentService;
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
 * @see GlobalMarketService
 * @see DashboardSentimentDto
 * @see IndexPerformanceDto
 * @see GlobalMarketDto
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final SentimentService sentimentService;
    private final IndexPerformanceService indexPerformanceService;
    private final GlobalMarketService globalMarketService;

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

    /**
     * Global piyasa verilerini dondurur.
     *
     * <p>HTTP GET {@code /api/v1/dashboard/global-markets}</p>
     *
     * <p>Emtia (altin, gumus, petrol), doviz (USD/TRY, EUR/TRY), kripto (BTC)
     * ve uluslararasi endeks (S&P 500, DAX, Nikkei vb.) verilerini dondurur.
     * Veriler volatile cache ile 60 saniye saklanir.</p>
     *
     * @return global piyasa listesini iceren {@link GlobalMarketDto} listesi;
     *         hata durumunda bos liste
     */
    @GetMapping("/global-markets")
    public ResponseEntity<List<GlobalMarketDto>> getGlobalMarkets() {
        return ResponseEntity.ok(globalMarketService.getGlobalMarkets());
    }
}
