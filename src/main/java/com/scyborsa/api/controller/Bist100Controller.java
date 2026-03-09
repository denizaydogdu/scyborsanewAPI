package com.scyborsa.api.controller;

import com.scyborsa.api.dto.SectorStockDto;
import com.scyborsa.api.service.Bist100Service;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * BIST endeks hisse verilerine erisim saglayan REST controller.
 *
 * <p>Dort endpoint sunar:</p>
 * <ul>
 *   <li>{@code GET /api/v1/tum-hisseler} - Tum BIST hisseleri</li>
 *   <li>{@code GET /api/v1/bist100} - BIST 100 endeks hisseleri</li>
 *   <li>{@code GET /api/v1/bist50} - BIST 50 endeks hisseleri</li>
 *   <li>{@code GET /api/v1/bist30} - BIST 30 endeks hisseleri</li>
 * </ul>
 *
 * @see Bist100Service
 * @see SectorStockDto
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class Bist100Controller {

    private final Bist100Service bist100Service;

    /**
     * Tum BIST hisselerinin guncel piyasa verilerini dondurur.
     *
     * @return tum hisse listesi (~560 hisse)
     */
    @GetMapping("/tum-hisseler")
    public List<SectorStockDto> getAllStocks() {
        return bist100Service.getAllStocks();
    }

    /**
     * BIST 100 endeksindeki hisselerin guncel piyasa verilerini dondurur.
     *
     * @return BIST 100 hisse listesi (~100 hisse)
     */
    @GetMapping("/bist100")
    public List<SectorStockDto> getBist100Stocks() {
        return bist100Service.getBist100Stocks();
    }

    /**
     * BIST 50 endeksindeki hisselerin guncel piyasa verilerini dondurur.
     *
     * @return BIST 50 hisse listesi (~50 hisse)
     */
    @GetMapping("/bist50")
    public List<SectorStockDto> getBist50Stocks() {
        return bist100Service.getBist50Stocks();
    }

    /**
     * BIST 30 endeksindeki hisselerin guncel piyasa verilerini dondurur.
     *
     * @return BIST 30 hisse listesi (~30 hisse)
     */
    @GetMapping("/bist30")
    public List<SectorStockDto> getBist30Stocks() {
        return bist100Service.getBist30Stocks();
    }
}
