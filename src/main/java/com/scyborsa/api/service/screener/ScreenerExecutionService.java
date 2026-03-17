package com.scyborsa.api.service.screener;

import com.scyborsa.api.config.ScreenerScanBodyRegistry;
import com.scyborsa.api.dto.screener.ScanBodyDefinition;
import com.scyborsa.api.dto.screener.TvScreenerResponse;
import com.scyborsa.api.enums.ScreenerTimesEnum;
import com.scyborsa.api.enums.ScreenerTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Tüm screener taramalarını orkestre eden ana servis.
 *
 * <p>Her screener türü için scan body'leri paralel çalıştırır, sonuçları
 * toplar ve veritabanına kaydeder. Türler arası sıralı çalışır (rate limit),
 * tür içi scan body'ler paralel çalışır.</p>
 *
 * <h3>Çalışma Akışı:</h3>
 * <pre>
 * executeAllScreeners(timeSlot)
 *   └── for each ScreenerTypeEnum (SIRALI):
 *        ├── ScreenerScanBodyRegistry.getScanBodies(type)
 *        ├── CompletableFuture: TradingViewScreenerClient.executeScan(body) × N (PARALEL)
 *        ├── ScreenerResultPersistService.saveResults(results)
 *        └── sleep(1500ms) // Rate limit koruması
 * </pre>
 *
 * @see TradingViewScreenerClient
 * @see ScreenerResultPersistService
 * @see ScreenerScanBodyRegistry
 */
@Slf4j
@Service
public class ScreenerExecutionService {

    /** Tek bir scan body icin maksimum bekleme suresi (saniye). */
    private static final long SCAN_TIMEOUT_SECONDS = 30L;

    /** Screener turleri arasi rate limit bekleme suresi (milisaniye). */
    @Value("${screener.rate-limit-sleep-ms:1500}")
    private long rateLimitSleepMs;

    /** Scan body JSON dosyalarini sunan registry. */
    private final ScreenerScanBodyRegistry scanBodyRegistry;
    /** TradingView Scanner API client. */
    private final TradingViewScreenerClient screenerClient;
    /** Tarama sonuclarini veritabanina kaydeden servis. */
    private final ScreenerResultPersistService persistService;
    /** Paralel tarama icin thread pool executor. */
    private final Executor screenerExecutor;

    /**
     * Constructor injection ile bağımlılıkları alır.
     *
     * @param scanBodyRegistry scan body JSON dosyalarını sunan registry
     * @param screenerClient TradingView API client
     * @param persistService veritabanı kayıt servisi
     * @param screenerExecutor paralel tarama için thread pool executor
     */
    public ScreenerExecutionService(
            ScreenerScanBodyRegistry scanBodyRegistry,
            TradingViewScreenerClient screenerClient,
            ScreenerResultPersistService persistService,
            @Qualifier("screenerTaskExecutor") Executor screenerExecutor) {
        this.scanBodyRegistry = scanBodyRegistry;
        this.screenerClient = screenerClient;
        this.persistService = persistService;
        this.screenerExecutor = screenerExecutor;
    }

    /**
     * Tüm screener türlerini belirtilen zaman dilimi için çalıştırır.
     *
     * <p>Screener türleri sıralı, her tür içindeki scan body'ler paralel çalışır.
     * Bir tür hata verirse diğerleri çalışmaya devam eder (fault tolerant).</p>
     *
     * @param timeSlot çalıştırılacak zaman dilimi
     */
    public void executeAllScreeners(ScreenerTimesEnum timeSlot) {
        int totalSaved = 0;

        for (ScreenerTypeEnum type : ScreenerTypeEnum.values()) {
            try {
                // MARKET_SUMMARY sadece Telegram job'u için; günlük taramada çalıştırılmaz (SC uyumluluk)
                if (type == ScreenerTypeEnum.MARKET_SUMMARY) {
                    continue;
                }

                // Scan body yoksa API çağrısı yapılmaz, sleep gerekmez
                if (scanBodyRegistry.getScanBodies(type).isEmpty()) {
                    continue;
                }

                int saved = executeScreenerType(type, timeSlot);
                totalSaved += saved;

                // Rate limit koruması — yalnızca API çağrısı yapıldıysa bekle
                Thread.sleep(rateLimitSleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[SCREENER-EXEC] İşlem interrupted: {}", type.getCode());
                break;
            } catch (Exception e) {
                log.error("[SCREENER-EXEC] Tür hatası: {}", type.getCode(), e);
                // Diğer türler devam etsin
            }
        }

        log.info("[SCREENER-EXEC] Tüm taramalar tamamlandı: toplam {} kayıt, zaman={}",
                totalSaved, timeSlot.getTimeStr());
    }

    /**
     * Tek bir screener türünün tüm scan body'lerini paralel çalıştırır.
     *
     * @param type çalıştırılacak screener türü
     * @param timeSlot zaman dilimi
     * @return kaydedilen kayıt sayısı
     */
    private int executeScreenerType(ScreenerTypeEnum type, ScreenerTimesEnum timeSlot) {
        List<ScanBodyDefinition> scanBodies = scanBodyRegistry.getScanBodies(type);
        if (scanBodies.isEmpty()) {
            log.debug("[SCREENER-EXEC] {} için scan body bulunamadı", type.getCode());
            return 0;
        }

        log.info("[SCREENER-EXEC] {} başlıyor: {} scan body", type.getCode(), scanBodies.size());

        // Tür içi scan body'ler paralel çalışır
        List<CompletableFuture<TvScreenerResponse>> futures = scanBodies.stream()
                .map(body -> CompletableFuture.supplyAsync(
                        () -> screenerClient.executeScan(body), screenerExecutor)
                        .orTimeout(SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .exceptionally(ex -> {
                            log.warn("[SCREENER-EXEC] Scan timeout/hata: {} / {}", type.getCode(), body.name());
                            return null;
                        }))
                .toList();

        // Tüm sonuçları topla
        List<TvScreenerResponse> responses = futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .toList();

        if (responses.isEmpty()) {
            log.warn("[SCREENER-EXEC] {} için sonuç alınamadı", type.getCode());
            return 0;
        }

        // Sonuçları kaydet
        int saved = persistService.saveResults(new ArrayList<>(responses), timeSlot, type);
        log.info("[SCREENER-EXEC] {} tamamlandı: {}/{} başarılı, {} kayıt",
                type.getCode(), responses.size(), scanBodies.size(), saved);

        return saved;
    }
}
