package com.scyborsa.api.service.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.dto.screener.ScanBodyDefinition;
import com.scyborsa.api.dto.screener.TvScreenerResponse;
import com.scyborsa.api.service.screener.TradingViewScreenerClient;
import com.scyborsa.api.utils.BistTradingCalendar;
import com.scyborsa.api.utils.ProfileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fiyat alarmi batch tarama job'u.
 *
 * <p>Seans saatlerinde (09:55-18:05, is gunleri) periyodik olarak
 * aktif alarmi olan hisselerin fiyatlarini TradingView Scanner API'den
 * ceker ve {@link PriceAlertEngine#checkPrice(String, double)} ile kontrol eder.</p>
 *
 * <p>Bu job sayesinde kullanicinin hisse detay sayfasinda olmasi gerekmez —
 * alarmlar arka planda tetiklenir.</p>
 *
 * @see PriceAlertEngine
 * @see TradingViewScreenerClient
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceAlertScanJob {

    private final PriceAlertEngine alertEngine;
    private final TradingViewScreenerClient screenerClient;
    private final ObjectMapper objectMapper;
    private final ProfileUtils profileUtils;

    /**
     * Her 30 saniyede aktif alarmi olan hisselerin fiyatlarini kontrol eder.
     *
     * <p>Sadece seans saatlerinde ve is gunlerinde calisir.
     * Aktif alarm yoksa islem yapmaz.</p>
     */
    @Scheduled(fixedDelay = 30000)
    public void scanAlertPrices() {
        // Prod profil kontrolu
        if (!profileUtils.isProdProfile()) {
            return;
        }
        // Seans saati kontrolu (09:55-18:25 + is gunu)
        if (!BistTradingCalendar.isMarketOpen()) {
            return;
        }

        // Aktif alarm olan hisseleri al
        Set<String> stockCodes = alertEngine.getActiveStockCodes();
        if (stockCodes.isEmpty()) {
            return;
        }

        try {
            // TradingView Scanner API scan body olustur
            String scanBody = buildScanBody(stockCodes);
            ScanBodyDefinition scanDef = new ScanBodyDefinition("ALERT_PRICE_CHECK", scanBody);

            TvScreenerResponse response = screenerClient.executeScan(scanDef);
            if (response == null || response.getData() == null) {
                log.debug("[ALERT-SCAN] Scanner API yanit vermedi");
                return;
            }

            int checked = 0;
            for (TvScreenerResponse.DataItem item : response.getData()) {
                if (item.getS() == null || item.getD() == null || item.getD().isEmpty()) {
                    continue;
                }

                // s = "BIST:THYAO" → "THYAO"
                String symbol = item.getS();
                if (symbol.contains(":")) {
                    symbol = symbol.substring(symbol.indexOf(':') + 1);
                }

                // d[0] = close price
                Object closeObj = item.getD().get(0);
                if (closeObj instanceof Number) {
                    double price = ((Number) closeObj).doubleValue();
                    if (price > 0) {
                        alertEngine.checkPrice(symbol, price);
                        checked++;
                    }
                }
            }

            if (checked > 0) {
                log.debug("[ALERT-SCAN] {} hisse fiyat kontrolu yapildi", checked);
            }
        } catch (Exception e) {
            log.warn("[ALERT-SCAN] Fiyat tarama hatasi: {}", e.getMessage());
        }
    }

    /**
     * Aktif alarm olan hisseler icin TradingView Scanner API scan body JSON'u olusturur.
     *
     * @param stockCodes alarm olan hisse kodlari
     * @return scan body JSON string
     */
    private String buildScanBody(Set<String> stockCodes) {
        try {
            var tickers = stockCodes.stream()
                    .map(code -> "BIST:" + code)
                    .collect(Collectors.toList());
            var body = java.util.Map.of(
                    "symbols", java.util.Map.of("tickers", tickers),
                    "columns", java.util.List.of("close")
            );
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            log.error("[ALERT-SCAN] Scan body olusturulamadi", e);
            return "{}";
        }
    }
}
