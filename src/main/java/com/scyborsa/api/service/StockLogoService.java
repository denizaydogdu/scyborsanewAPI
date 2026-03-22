package com.scyborsa.api.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Hisse ve araci kurum logolarini CDN'lerden indirip iki katmanli cache ile saklayan servis.
 *
 * <p>Uc katmanli logo erisim stratejisi:</p>
 * <ol>
 *   <li><b>In-memory cache:</b> {@link ConcurrentHashMap} (en hizli, API restart'ta kaybolur)</li>
 *   <li><b>Disk cache:</b> Dosya sistemi ({@code logo.cache.dir}, API restart'ta korunur)</li>
 *   <li><b>CDN:</b> TradingView / Fintables (en yavas, network bagimliligi)</li>
 * </ol>
 *
 * <p>Iki farkli logo kaynagi desteklenir:</p>
 * <ul>
 *   <li><b>Hisse logolari:</b> TradingView CDN (SVG format)</li>
 *   <li><b>Araci kurum logolari:</b> Fintables CDN (PNG/JPEG format)</li>
 * </ul>
 *
 * <p>Ilk istekte CDN'den indirir, sonraki isteklerde in-memory veya disk cache'ten
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

    /** TradingView hisse logo CDN temel URL'i. */
    private static final String STOCK_CDN_BASE = "https://s3-symbol-logo.tradingview.com/";

    /** TradingView hisse logo CDN dosya eki. */
    private static final String STOCK_CDN_SUFFIX = "--big.svg";

    /** Fintables araci kurum logo CDN temel URL'i. */
    private static final String BROKERAGE_CDN_BASE = "https://storage.fintables.com/media/uploads/brokerage-logos/";

    /** Bos byte dizisi — bulunamayan logolar icin sentinel deger. */
    private static final byte[] EMPTY = new byte[0];

    /** Hisse logoid format dogrulama deseni. */
    private static final Pattern VALID_LOGOID = Pattern.compile("^[a-z0-9-]{1,100}$");

    /** Araci kurum logo dosya adi format dogrulama deseni. */
    private static final Pattern VALID_BROKERAGE_FILENAME = Pattern.compile("^[a-z0-9_-]{1,100}\\.(png|jpeg|jpg|svg|webp)$");

    /** Maksimum cache boyutu (her iki logo cache icin). */
    private static final int MAX_CACHE_SIZE = 2000;

    /** EMPTY sentinel icin negative cache suresi (1 saat). Gecici CDN hatalarinda kalici cache poisoning onlenir. */
    private static final long NEGATIVE_CACHE_TTL_MS = 3_600_000L;

    /** CDN yanit body maksimum boyutu (512KB). Beklenmedik buyuk yanitlari reddeder. */
    private static final int MAX_RESPONSE_SIZE = 512_000;

    /** Disk cache alt dizini: hisse logolari. */
    private static final String STOCK_SUBDIR = "stock";

    /** Disk cache alt dizini: araci kurum logolari. */
    private static final String BROKERAGE_SUBDIR = "brokerage";

    /** Hisse logo cache: logoid -> SVG byte dizisi. */
    private final ConcurrentHashMap<String, byte[]> stockLogoCache = new ConcurrentHashMap<>();

    /** Araci kurum logo cache: dosya adi -> logo byte dizisi. */
    private final ConcurrentHashMap<String, byte[]> brokerageLogoCache = new ConcurrentHashMap<>();
    /** EMPTY sentinel cache'lenen anahtarlarin timestamp'lerini tutar (negative cache TTL icin). */
    private final ConcurrentHashMap<String, Long> negativeCacheTimestamps = new ConcurrentHashMap<>();
    /** HTTP istekleri icin Java 11 HttpClient. */
    private final HttpClient httpClient;

    /** Disk cache ana dizini. Yapilandirma: {@code logo.cache.dir} property. */
    @Value("${logo.cache.dir:./cache/logos}")
    private String cacheDir;

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
     * Disk cache dizinlerini olusturur.
     *
     * <p>Uygulama baslatildiginda {@code {cacheDir}/stock} ve {@code {cacheDir}/brokerage}
     * alt dizinlerini olusturur. Dizin olusturulamazsa uyari loglar, uygulama calismaya devam eder.</p>
     */
    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(Path.of(cacheDir, STOCK_SUBDIR));
            Files.createDirectories(Path.of(cacheDir, BROKERAGE_SUBDIR));
            log.info("[LOGO] Disk cache dizini: {}", cacheDir);
        } catch (IOException e) {
            log.warn("[LOGO] Disk cache dizini olusturulamadi: {}", cacheDir, e);
        }
    }

    /**
     * Logoid'e ait SVG hisse logo verisini dondurur (in-memory -> disk -> CDN).
     *
     * <p>Oncelik sirasi: in-memory cache, disk cache, CDN indirme.
     * CDN'den basariyla indirilen logolar hem diske hem in-memory cache'e yazilir.
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

        // Disk cache kontrolu
        byte[] diskData = readFromDisk(STOCK_SUBDIR, logoid + ".svg");
        if (diskData != null) {
            stockLogoCache.put(logoid, diskData);
            return diskData;
        }

        if (stockLogoCache.size() >= MAX_CACHE_SIZE) {
            return EMPTY;
        }
        return stockLogoCache.computeIfAbsent(logoid, this::fetchStockLogo);
    }

    /**
     * Araci kurum logo dosyasini dondurur (in-memory -> disk -> CDN).
     *
     * <p>Oncelik sirasi: in-memory cache, disk cache, CDN indirme.
     * CDN'den basariyla indirilen logolar hem diske hem in-memory cache'e yazilir.
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

        // Disk cache kontrolu
        byte[] diskData = readFromDisk(BROKERAGE_SUBDIR, filename);
        if (diskData != null) {
            brokerageLogoCache.put(filename, diskData);
            return diskData;
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
        byte[] data = fetchFromUrl(STOCK_CDN_BASE + logoid + STOCK_CDN_SUFFIX, "STOCK-LOGO", logoid);
        if (data != null && data.length > 0 && data != EMPTY) {
            writeToDisk(STOCK_SUBDIR, logoid + ".svg", data);
        }
        return data;
    }

    /**
     * Fintables CDN'den araci kurum logosu indirir.
     *
     * @param filename logo dosya adi
     * @return logo byte dizisi veya EMPTY sentinel (hata/404 durumunda)
     */
    private byte[] fetchBrokerageLogo(String filename) {
        byte[] data = fetchFromUrl(BROKERAGE_CDN_BASE + filename, "BROKERAGE-LOGO", filename);
        if (data != null && data.length > 0 && data != EMPTY) {
            writeToDisk(BROKERAGE_SUBDIR, filename, data);
        }
        return data;
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
     * Disk cache'ten logo dosyasi okur.
     *
     * @param subDir alt dizin adi ("stock" veya "brokerage")
     * @param filename dosya adi (orn. "turk-hava-yollari.svg")
     * @return logo byte dizisi veya {@code null} (dosya bulunamazsa veya okuma hatasi)
     */
    private byte[] readFromDisk(String subDir, String filename) {
        try {
            Path path = Path.of(cacheDir, subDir, filename);
            if (Files.exists(path)) {
                byte[] data = Files.readAllBytes(path);
                if (data.length > 0) {
                    log.debug("[LOGO] Disk cache'ten okundu: {}/{}", subDir, filename);
                    return data;
                }
            }
        } catch (IOException e) {
            log.debug("[LOGO] Disk okuma hatasi: {}/{}", subDir, filename);
        }
        return null;
    }

    /**
     * Logo verisini disk cache'e atomik olarak yazar.
     *
     * <p>Once gecici dosyaya ({@code .tmp} uzantili) yazar, sonra atomik {@code move}
     * ile hedef dosyaya tasir. Atomik move desteklenmezse (cross-filesystem),
     * standart {@code REPLACE_EXISTING} ile fallback yapar.</p>
     *
     * @param subDir alt dizin adi ("stock" veya "brokerage")
     * @param filename dosya adi (orn. "turk-hava-yollari.svg")
     * @param data yazilacak logo byte dizisi
     */
    private void writeToDisk(String subDir, String filename, byte[] data) {
        try {
            Path dir = Path.of(cacheDir, subDir);
            Path target = dir.resolve(filename);
            Path temp = dir.resolve(filename + ".tmp");
            Files.write(temp, data);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // Cross-filesystem fallback: atomik move desteklenmiyorsa standart replace
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.debug("[LOGO] Disk yazma hatasi: {}/{}", subDir, filename);
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
