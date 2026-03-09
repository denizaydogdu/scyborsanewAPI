package com.scyborsa.api.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Hisse ve araci kurum logolarini CDN'lerden indirip in-memory cache'leyen servis.
 *
 * <p>Iki farkli logo kaynagi desteklenir:</p>
 * <ul>
 *   <li><b>Hisse logolari:</b> TradingView CDN (SVG format)</li>
 *   <li><b>Araci kurum logolari:</b> Fintables CDN (PNG/JPEG format)</li>
 * </ul>
 *
 * <p>Ilk istekte CDN'den indirir, sonraki isteklerde {@link ConcurrentHashMap} cache'ten
 * servis eder. CDN'de bulunamayan logolar icin {@code EMPTY} sentinel doner.
 * EMPTY sentinel'ler 1 saat sonra expire olur (gecici CDN hatalarinda kalici cache poisoning onlenir).
 * CDN'den gelen yanit boyutu 512KB sinirini asarsa reddedilir.</p>
 *
 * @see com.scyborsa.api.controller.StockLogoController#getStockLogoImage(String)
 * @see com.scyborsa.api.controller.StockLogoController#getBrokerageLogoImage(String)
 */
@Slf4j
@Service
public class StockLogoService {

    private static final String STOCK_CDN_BASE = "https://s3-symbol-logo.tradingview.com/";
    private static final String STOCK_CDN_SUFFIX = "--big.svg";
    private static final String BROKERAGE_CDN_BASE = "https://storage.fintables.com/media/uploads/brokerage-logos/";

    private static final byte[] EMPTY = new byte[0];
    private static final Pattern VALID_LOGOID = Pattern.compile("^[a-z0-9-]{1,100}$");
    private static final Pattern VALID_BROKERAGE_FILENAME = Pattern.compile("^[a-z0-9_-]{1,100}\\.(png|jpeg|jpg|svg)$");
    private static final int MAX_CACHE_SIZE = 2000;

    /** EMPTY sentinel icin negative cache suresi (1 saat). Gecici CDN hatalarinda kalici cache poisoning onlenir. */
    private static final long NEGATIVE_CACHE_TTL_MS = 3_600_000L;

    /** CDN yanit body maksimum boyutu (512KB). Beklenmedik buyuk yanitlari reddeder. */
    private static final int MAX_RESPONSE_SIZE = 512_000;

    private final ConcurrentHashMap<String, byte[]> stockLogoCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, byte[]> brokerageLogoCache = new ConcurrentHashMap<>();
    /** EMPTY sentinel cache'lenen anahtarlarin timestamp'lerini tutar (negative cache TTL icin). */
    private final ConcurrentHashMap<String, Long> negativeCacheTimestamps = new ConcurrentHashMap<>();
    private final HttpClient httpClient;

    /**
     * StockLogoService constructor.
     * Java 11 HttpClient ile 5 saniye connect timeout yapilandirir.
     */
    public StockLogoService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Logoid'e ait SVG hisse logo verisini dondurur (cache-first).
     *
     * <p>Ilk istekte TradingView CDN'den indirir, sonraki isteklerde cache'ten servis eder.
     * CDN'de bulunamazsa {@code EMPTY} sentinel doner. EMPTY sentinel'ler
     * {@value #NEGATIVE_CACHE_TTL_MS}ms sonra expire olur ve yeniden denenir.</p>
     *
     * @param logoid TradingView logoid (orn. "turk-hava-yollari")
     * @return SVG byte dizisi veya bos dizi (bulunamazsa)
     */
    public byte[] getLogo(String logoid) {
        if (logoid == null || !VALID_LOGOID.matcher(logoid).matches()) {
            return EMPTY;
        }
        // Negative cache TTL kontrolu: EMPTY sentinel suresi dolduysa cache'ten kaldir, yeniden dene
        byte[] cached = stockLogoCache.get(logoid);
        if (cached != null && cached.length == 0 && isNegativeCacheExpired(logoid)) {
            stockLogoCache.remove(logoid);
            negativeCacheTimestamps.remove(logoid);
            cached = null;
        }
        if (cached != null) {
            return cached;
        }
        if (stockLogoCache.size() >= MAX_CACHE_SIZE) {
            return EMPTY;
        }
        return stockLogoCache.computeIfAbsent(logoid, this::fetchStockLogo);
    }

    /**
     * Araci kurum logo dosyasini dondurur (cache-first).
     *
     * <p>Ilk istekte Fintables CDN'den indirir, sonraki isteklerde cache'ten servis eder.
     * PNG/JPEG/SVG formatinda dosyalar desteklenir. EMPTY sentinel'ler
     * {@value #NEGATIVE_CACHE_TTL_MS}ms sonra expire olur ve yeniden denenir.</p>
     *
     * @param filename logo dosya adi (orn. "alnus_yatirim_icon.png")
     * @return logo byte dizisi veya bos dizi (bulunamazsa)
     */
    public byte[] getBrokerageLogo(String filename) {
        if (filename == null || !VALID_BROKERAGE_FILENAME.matcher(filename).matches()) {
            return EMPTY;
        }
        // Negative cache TTL kontrolu: EMPTY sentinel suresi dolduysa cache'ten kaldir, yeniden dene
        byte[] cached = brokerageLogoCache.get(filename);
        if (cached != null && cached.length == 0 && isNegativeCacheExpired(filename)) {
            brokerageLogoCache.remove(filename);
            negativeCacheTimestamps.remove(filename);
            cached = null;
        }
        if (cached != null) {
            return cached;
        }
        if (brokerageLogoCache.size() >= MAX_CACHE_SIZE) {
            return EMPTY;
        }
        return brokerageLogoCache.computeIfAbsent(filename, this::fetchBrokerageLogo);
    }

    /**
     * TradingView CDN'den SVG hisse logosu indirir.
     *
     * @param logoid TradingView logoid
     * @return SVG byte dizisi veya EMPTY sentinel (hata/404 durumunda)
     */
    private byte[] fetchStockLogo(String logoid) {
        return fetchFromUrl(STOCK_CDN_BASE + logoid + STOCK_CDN_SUFFIX, "STOCK-LOGO", logoid);
    }

    /**
     * Fintables CDN'den araci kurum logosu indirir.
     *
     * @param filename logo dosya adi
     * @return logo byte dizisi veya EMPTY sentinel (hata/404 durumunda)
     */
    private byte[] fetchBrokerageLogo(String filename) {
        return fetchFromUrl(BROKERAGE_CDN_BASE + filename, "BROKERAGE-LOGO", filename);
    }

    /**
     * Belirtilen URL'den logo dosyasi indirir.
     *
     * <p>Boyut siniri: yanit body'si {@value #MAX_RESPONSE_SIZE} byte'i asarsa reddedilir.
     * EMPTY sentinel dondurulen durumlarda negative cache timestamp kaydedilir.</p>
     *
     * @param url    tam CDN URL'si
     * @param logTag log etiketi
     * @param key    cache anahtari (log icin)
     * @return logo byte dizisi veya EMPTY sentinel
     */
    private byte[] fetchFromUrl(String url, String logTag, String key) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() == 200) {
                byte[] body = response.body();
                if (body.length > MAX_RESPONSE_SIZE) {
                    log.warn("[{}] CDN yanit boyutu siniri asildi: {} ({} bytes > {} max)",
                            logTag, key, body.length, MAX_RESPONSE_SIZE);
                    recordNegativeCache(key);
                    return EMPTY;
                }
                log.debug("[{}] CDN'den indirildi: {}", logTag, key);
                return body;
            }

            log.warn("[{}] CDN {}: {}", logTag, response.statusCode(), key);
            recordNegativeCache(key);
            return EMPTY;
        } catch (Exception e) {
            log.warn("[{}] CDN fetch hatasi: {} - {}", logTag, key, e.getMessage());
            recordNegativeCache(key);
            return EMPTY;
        }
    }

    /**
     * EMPTY sentinel icin negative cache timestamp kaydeder.
     *
     * @param key cache anahtari
     */
    private void recordNegativeCache(String key) {
        negativeCacheTimestamps.put(key, System.currentTimeMillis());
    }

    /**
     * Belirtilen anahtarin negative cache TTL'sinin dolup dolmadigini kontrol eder.
     *
     * @param key cache anahtari
     * @return TTL dolduysa {@code true}, dolmadiysa {@code false}
     */
    private boolean isNegativeCacheExpired(String key) {
        Long timestamp = negativeCacheTimestamps.get(key);
        if (timestamp == null) {
            return true; // timestamp yoksa expired kabul et
        }
        return (System.currentTimeMillis() - timestamp) >= NEGATIVE_CACHE_TTL_MS;
    }
}
