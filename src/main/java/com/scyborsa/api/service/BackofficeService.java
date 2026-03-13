package com.scyborsa.api.service;

import com.scyborsa.api.config.ScreenerScanBodyRegistry;
import com.scyborsa.api.config.SectorDefinitionRegistry;
import com.scyborsa.api.config.TelegramConfig;
import com.scyborsa.api.dto.backoffice.BackofficeDashboardDto;
import com.scyborsa.api.dto.backoffice.ScreenerResultSummaryDto;
import com.scyborsa.api.dto.backoffice.StockDto;
import com.scyborsa.api.model.screener.ScreenerResultModel;
import com.scyborsa.api.model.StockModel;
import com.scyborsa.api.repository.AnalistRepository;
import com.scyborsa.api.repository.AraciKurumRepository;
import com.scyborsa.api.repository.ModelPortfoyKurumRepository;
import com.scyborsa.api.repository.ScreenerResultRepository;
import com.scyborsa.api.repository.StockModelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Backoffice yonetim paneli is mantigi servisi.
 *
 * <p>Dashboard KPI verileri, hisse ban/unban islemleri ve
 * stok listesi sorgularini yonetir.</p>
 *
 * @see BackofficeDashboardDto
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackofficeService {

    private final StockModelRepository stockModelRepository;
    private final AnalistRepository analistRepository;
    private final AraciKurumRepository araciKurumRepository;
    private final ModelPortfoyKurumRepository kurumRepository;
    private final ScreenerResultRepository screenerResultRepository;
    private final SectorDefinitionRegistry sectorDefinitionRegistry;
    private final ScreenerScanBodyRegistry screenerScanBodyRegistry;
    private final TelegramConfig telegramConfig;
    private final Environment environment;

    /**
     * Dashboard KPI verilerini toplar ve dondurur.
     *
     * <p>Hisse, icerik, tarama ve sistem istatistiklerini tek bir DTO'da birlestirir.
     * Her kategori icin farkli repository ve registry sorgulari yapilir.</p>
     *
     * @return dashboard KPI verileri
     */
    public BackofficeDashboardDto getDashboard() {
        LocalDate today = LocalDate.now();
        LocalDateTime todayStart = today.atStartOfDay();

        // Hisse istatistikleri
        long totalStocks = stockModelRepository.count();
        long activeStocks = stockModelRepository.countActiveStocks();
        long bannedStocks = totalStocks - activeStocks;

        // Icerik istatistikleri
        long analistCount = analistRepository.count();
        long araciKurumCount = araciKurumRepository.countByAktifTrue();
        long kurumCount = kurumRepository.count();
        long sektorCount = sectorDefinitionRegistry.getAll().size();

        // Tarama istatistikleri
        long todayTotal = screenerResultRepository.countByScreenerDay(today);
        long telegramSent = screenerResultRepository.countByScreenerDayAndTelegramSentTrue(today);
        long telegramUnsent = screenerResultRepository.countTodayUnsent(today);
        long tpSlPending = screenerResultRepository.countPendingTpSlChecks(todayStart);
        long scanBodyCount = screenerScanBodyRegistry.getTotalCount();
        boolean telegramEnabled = telegramConfig.isEnabled();

        // Sistem bilgileri
        Runtime runtime = Runtime.getRuntime();
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        long usedHeap = runtime.totalMemory() - runtime.freeMemory();
        long maxHeap = runtime.maxMemory();
        String[] profiles = environment.getActiveProfiles();
        String activeProfile = profiles.length > 0 ? String.join(",", profiles) : "default";

        return BackofficeDashboardDto.builder()
                .stocks(BackofficeDashboardDto.StockStats.builder()
                        .total(totalStocks)
                        .active(activeStocks)
                        .banned(bannedStocks)
                        .build())
                .content(BackofficeDashboardDto.ContentStats.builder()
                        .analistCount(analistCount)
                        .araciKurumCount(araciKurumCount)
                        .kurumCount(kurumCount)
                        .sektorCount(sektorCount)
                        .build())
                .screener(BackofficeDashboardDto.ScreenerStats.builder()
                        .todayTotal(todayTotal)
                        .telegramSent(telegramSent)
                        .telegramUnsent(telegramUnsent)
                        .tpSlPending(tpSlPending)
                        .scanBodyCount(scanBodyCount)
                        .telegramEnabled(telegramEnabled)
                        .build())
                .system(BackofficeDashboardDto.SystemSummary.builder()
                        .uptimeMs(uptimeMs)
                        .usedHeapBytes(usedHeap)
                        .maxHeapBytes(maxHeap)
                        .activeProfile(activeProfile)
                        .build())
                .build();
    }

    /**
     * Hisse listesini filtreye gore getirir.
     *
     * @param filtre "all", "aktif" veya "yasakli"
     * @return filtrelenmis hisse DTO listesi
     */
    public List<StockDto> getStocks(String filtre) {
        List<StockModel> stocks;
        if ("aktif".equalsIgnoreCase(filtre)) {
            stocks = stockModelRepository.findActiveStocks();
        } else if ("yasakli".equalsIgnoreCase(filtre)) {
            stocks = stockModelRepository.findAllOrdered().stream()
                    .filter(s -> Boolean.TRUE.equals(s.getIsBanned()))
                    .toList();
        } else {
            stocks = stockModelRepository.findAllOrdered();
        }
        return stocks.stream().map(this::toStockDto).toList();
    }

    /**
     * Hisseyi yasaklar.
     *
     * @param stockName yasaklanacak hisse kodu
     * @param neden yasaklama nedeni
     * @throws IllegalArgumentException hisse bulunamazsa
     */
    public void yasakla(String stockName, String neden) {
        validateStockName(stockName);
        StockModel stock = stockModelRepository.findByStockName(stockName)
                .orElseThrow(() -> new IllegalArgumentException("Hisse bulunamadi: " + stockName));
        stock.yasakla(neden);
        stockModelRepository.save(stock);
        log.info("[BACKOFFICE] Hisse yasaklandi: {} — neden: {}", stockName, neden);
    }

    /**
     * Hissenin yasagini kaldirir.
     *
     * @param stockName yasagi kaldirilacak hisse kodu
     * @throws IllegalArgumentException hisse bulunamazsa
     */
    public void yasakKaldir(String stockName) {
        validateStockName(stockName);
        StockModel stock = stockModelRepository.findByStockName(stockName)
                .orElseThrow(() -> new IllegalArgumentException("Hisse bulunamadi: " + stockName));
        stock.yasakKaldir();
        stockModelRepository.save(stock);
        log.info("[BACKOFFICE] Hisse yasagi kaldirildi: {}", stockName);
    }

    /**
     * Bugunun tarama sonuclarini ozet DTO listesi olarak dondurur.
     *
     * @return bugunun tarama sonuclari
     */
    public List<ScreenerResultSummaryDto> getTodayScreenerResults() {
        return screenerResultRepository.findByScreenerDay(LocalDate.now())
                .stream()
                .map(this::toScreenerSummaryDto)
                .toList();
    }

    /**
     * StockModel entity'sini StockDto'ya donusturur.
     *
     * @param entity kaynak entity
     * @return donusturulmus DTO
     */
    private StockDto toStockDto(StockModel entity) {
        return StockDto.builder()
                .id(entity.getId())
                .stockName(entity.getStockName())
                .stockTypeId(entity.getStockTypeId())
                .isBanned(entity.getIsBanned())
                .bannedSituation(entity.getBannedSituation())
                .createTime(entity.getCreateTime() != null ? entity.getCreateTime().toString() : null)
                .build();
    }

    /**
     * ScreenerResultModel entity'sini ScreenerResultSummaryDto'ya donusturur.
     *
     * @param entity kaynak entity
     * @return donusturulmus DTO
     */
    private ScreenerResultSummaryDto toScreenerSummaryDto(ScreenerResultModel entity) {
        return ScreenerResultSummaryDto.builder()
                .stockName(entity.getStockName())
                .price(entity.getPrice())
                .percentage(entity.getPercentage())
                .screenerName(entity.getScreenerName())
                .screenerType(entity.getScreenerType() != null ? entity.getScreenerType().name() : null)
                .screenerTime(entity.getScreenerTime() != null ? entity.getScreenerTime().toString() : null)
                .telegramSent(entity.getTelegramSent())
                .tpCheckDone(entity.getTpCheckDone())
                .slCheckDone(entity.getSlCheckDone())
                .tpPrice(entity.getTpPrice())
                .slPrice(entity.getSlPrice())
                .build();
    }

    /**
     * Hisse kodunun BIST formatina uygun olup olmadigini dogrular.
     *
     * <p>BIST hisse kodlari 2-6 karakter uzunlugunda, buyuk harf ve rakam icerir.</p>
     *
     * @param stockName dogrulanacak hisse kodu
     * @throws IllegalArgumentException format uygun degilse
     */
    private void validateStockName(String stockName) {
        if (stockName == null || !stockName.matches("^[A-Z0-9]{2,6}$")) {
            throw new IllegalArgumentException("Gecersiz hisse kodu: " + stockName);
        }
    }
}
