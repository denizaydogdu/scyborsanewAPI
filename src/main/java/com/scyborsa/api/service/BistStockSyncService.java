package com.scyborsa.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.scyborsa.api.model.StockModel;
import com.scyborsa.api.repository.StockModelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * BIST hisse listesini api.velzon.tr'den çekip DB'ye senkronize eder.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BistStockSyncService {

    private final VelzonApiClient velzonApiClient;
    private final StockModelRepository stockModelRepository;

    private static final long DEFAULT_STOCK_TYPE_ID = 1L; // BIST hisse senedi

    /**
     * Tüm BIST hisselerini api.velzon.tr'den çekip DB'ye kaydeder.
     * Yeni hisseler eklenir, mevcut hisseler güncellenmez.
     *
     * @throws RuntimeException API veya DB hatası durumunda (job katmanı yakalar)
     */
    public void syncAllStocks() {
        log.info("[STOCK-SYNC] Hisse senkronizasyonu başlatıldı");

        List<String> stockNames = fetchAllBistStockNames();

        if (stockNames.isEmpty()) {
            log.warn("[STOCK-SYNC] API'den hisse listesi boş döndü, senkronizasyon atlandı");
            return;
        }

        log.info("[STOCK-SYNC] API'den {} hisse çekildi", stockNames.size());

        // DB'deki mevcut hisseleri al
        Set<String> existingStocks = stockModelRepository.findAllOrdered().stream()
                .map(StockModel::getStockName)
                .collect(Collectors.toSet());

        // Yeni hisseleri ekle
        int addedCount = 0;
        for (String stockName : stockNames) {
            if (!existingStocks.contains(stockName)) {
                StockModel stock = new StockModel();
                stock.setStockName(stockName);
                stock.setStockTypeId(DEFAULT_STOCK_TYPE_ID);
                stock.setIsBanned(false);
                stock.setCreateTime(LocalDateTime.now());
                stockModelRepository.save(stock);
                addedCount++;
            }
        }

        log.info("[STOCK-SYNC] Tamamlandı — {} mevcut, {} yeni eklendi, toplam API: {}",
                existingStocks.size(), addedCount, stockNames.size());
    }

    /**
     * api.velzon.tr'den tüm BIST hisse isimlerini çeker.
     * Screener endpoint'inden gelen data listesindeki sembol adlarını döner.
     */
    private List<String> fetchAllBistStockNames() {
        try {
            // ALL_STOCKS_WITH_INDICATORS screener endpoint'inden tüm hisseleri çek
            String path = "/api/screener/ALL_STOCKS_WITH_INDICATORS";
            Map<String, Object> response = velzonApiClient.get(path,
                    new TypeReference<Map<String, Object>>() {});

            if (response == null || !response.containsKey("data")) {
                return Collections.emptyList();
            }

            Object dataObj = response.get("data");
            if (dataObj instanceof List<?> dataList) {
                List<String> stockNames = new ArrayList<>();
                for (Object item : dataList) {
                    if (item instanceof Map<?, ?> map) {
                        Object symbol = map.get("s");
                        if (symbol != null) {
                            String name = symbol.toString().replace("BIST:", "");
                            stockNames.add(name);
                        }
                    }
                }
                return stockNames;
            }

            return Collections.emptyList();
        } catch (Exception e) {
            log.error("[STOCK-SYNC] API'den hisse listesi çekme hatası", e);
            return Collections.emptyList();
        }
    }

    /**
     * Aktif (yasaklı olmayan) hisse listesini döner.
     */
    public List<StockModel> getActiveStocks() {
        return stockModelRepository.findActiveStocks();
    }

    /**
     * Tüm hisse listesini döner.
     */
    public List<StockModel> getAllStocks() {
        return stockModelRepository.findAllOrdered();
    }

    /**
     * Hisse adına göre bulur.
     */
    public Optional<StockModel> findByName(String stockName) {
        return stockModelRepository.findByStockName(stockName);
    }
}
