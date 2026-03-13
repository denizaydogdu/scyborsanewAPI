package com.scyborsa.api.controller;

import com.scyborsa.api.service.market.Bist100Service;
import com.scyborsa.api.service.StockLogoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * Hisse ve araci kurum logo erisimi saglayan REST controller.
 *
 * <p>Uc endpoint sunar:</p>
 * <ul>
 *   <li>{@code GET /api/v1/stock-logos} - Tum hisselerin logoid haritasi</li>
 *   <li>{@code GET /api/v1/stock-logos/img/{logoid}} - SVG hisse logo proxy (cache-backed, CSP korumalı)</li>
 *   <li>{@code GET /api/v1/brokerage-logos/img/{filename}} - Araci kurum logo proxy (cache-backed)</li>
 * </ul>
 *
 * <p>SVG yanıtlarında XSS onlemi icin {@code Content-Security-Policy} header'i eklenir.</p>
 *
 * @see StockLogoService
 * @see Bist100Service#getStockLogoidMap()
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class StockLogoController {

    private final StockLogoService stockLogoService;
    private final Bist100Service bist100Service;

    /**
     * Tum hisselerin logoid haritasini dondurur (cache-based, sifir DB sorgusu).
     *
     * @return ticker → logoid map (orn. {"THYAO": "turk-hava-yollari"})
     */
    @GetMapping("/stock-logos")
    public Map<String, String> getStockLogos() {
        return bist100Service.getStockLogoidMap();
    }

    /**
     * Logoid'e ait SVG logo dosyasini proxy olarak dondurur.
     *
     * <p>TradingView CDN'den indirilen logo in-memory cache'lenir.
     * Browser cache: 7 gun ({@code Cache-Control: public, max-age=604800}).
     * SVG-based XSS onlemi icin {@code Content-Security-Policy} header'i eklenir.</p>
     *
     * @param logoid TradingView logoid (orn. "turk-hava-yollari")
     * @return SVG binary (image/svg+xml) veya 404
     */
    @GetMapping("/stock-logos/img/{logoid}")
    public ResponseEntity<byte[]> getStockLogoImage(@PathVariable String logoid) {
        byte[] svg = stockLogoService.getLogo(logoid);
        if (svg == null || svg.length == 0) {
            return ResponseEntity.notFound().build();
        }
        // Browser cache 7 gun — UI proxy bu header'i forward etmez, browser icin UI controller'da ayri set edilir
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("image/svg+xml"))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
                .body(svg);
    }

    /**
     * Araci kurum logo dosyasini proxy olarak dondurur.
     *
     * <p>Fintables CDN'den indirilen logo in-memory cache'lenir.
     * Browser cache: 7 gun ({@code Cache-Control: public, max-age=604800}).
     * PNG/JPEG/SVG formatlari desteklenir. SVG dosyalarinda XSS onlemi icin
     * {@code Content-Security-Policy} header'i eklenir.</p>
     *
     * @param filename logo dosya adi (orn. "alnus_yatirim_icon.png")
     * @return logo binary veya 404
     */
    @GetMapping("/brokerage-logos/img/{filename:.+}")
    public ResponseEntity<byte[]> getBrokerageLogoImage(@PathVariable String filename) {
        byte[] logo = stockLogoService.getBrokerageLogo(filename);
        if (logo == null || logo.length == 0) {
            return ResponseEntity.notFound().build();
        }
        boolean isSvg = filename.endsWith(".svg");
        MediaType mediaType = isSvg ? MediaType.valueOf("image/svg+xml")
                : filename.endsWith(".png") ? MediaType.IMAGE_PNG
                : MediaType.IMAGE_JPEG;
        // Browser cache 7 gun — UI proxy bu header'i forward etmez, browser icin UI controller'da ayri set edilir
        var builder = ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic());
        if (isSvg) {
            builder = builder.header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'");
        }
        return builder.body(logo);
    }
}
