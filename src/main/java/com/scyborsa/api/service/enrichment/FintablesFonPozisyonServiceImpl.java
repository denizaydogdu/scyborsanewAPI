package com.scyborsa.api.service.enrichment;

import com.scyborsa.api.config.FintablesApiConfig;
import com.scyborsa.api.dto.enrichment.FonPozisyon;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fintables fon pozisyon servis implementasyonu.
 *
 * <p>Fintables web sitesinden ({@code /sirketler/{STOCK}/fon-pozisyonlari}) HTML scraping
 * ile fonların hisse pozisyon bilgilerini çeker ve {@link FonPozisyon} listesine dönüştürür.</p>
 *
 * <p>Kimlik doğrulama: {@code Cookie: auth-token={bearerToken}} header'ı ile yapılır.</p>
 *
 * <p>Rate limiting: çağrılar arasında minimum 3500ms bekleme süresi uygulanır.</p>
 *
 * <p>Tüm hatalar yakalanır, boş liste döner — graceful degradation.</p>
 *
 * @see FintablesApiConfig
 * @see FonPozisyon
 */
@Slf4j
@Service
public class FintablesFonPozisyonServiceImpl implements FintablesFonPozisyonService {

    /** Fintables web URL'i (API değil, web scraping). */
    private static final String FINTABLES_WEB_URL = "https://fintables.com";

    /** Rate limit: çağrılar arası minimum bekleme süresi (ms). */
    private static final long RATE_LIMIT_MS = 3500L;

    /** Fintables API konfigurasyonu (token için). */
    private final FintablesApiConfig config;

    /** HTTP istemcisi (web scraping için). */
    private final OkHttpClient httpClient;

    /** Rate limit: son çağrı zamanı (epoch millis). */
    private final AtomicLong lastCallTime = new AtomicLong(0);

    /**
     * Constructor injection ile bağımlılıkları alır ve OkHttp client oluşturur.
     *
     * @param config Fintables API konfigurasyonu (bearer token için)
     */
    public FintablesFonPozisyonServiceImpl(FintablesApiConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(config.getRequestTimeoutSeconds(), TimeUnit.SECONDS)
                .build();
    }

    /**
     * Hisseyi tutan fonların pozisyon bilgilerini getirir.
     *
     * <p>Fintables web sitesinden HTML scraping ile fon kodu, nominal lot ve
     * ağırlık yüzdesi bilgilerini çeker.</p>
     *
     * @param stockName hisse kodu (ör: "GARAN")
     * @return fon pozisyon listesi; hata durumunda boş liste
     */
    @Override
    public List<FonPozisyon> getFonPozisyonlari(String stockName) {
        try {
            applyRateLimit();

            String url = FINTABLES_WEB_URL + "/sirketler/" + stockName + "/fon-pozisyonlari";
            String html = fetchHtml(url);

            if (html == null || html.isBlank()) {
                log.debug("[FonPozisyon] Boş HTML response: stockName={}", stockName);
                return Collections.emptyList();
            }

            List<FonPozisyon> result = parseHtml(html);
            log.debug("[FonPozisyon] {} fon pozisyonu döndü: stockName={}", result.size(), stockName);
            return result;
        } catch (Exception e) {
            log.error("[FonPozisyon] Fon pozisyonları alınırken hata: stockName={}", stockName, e);
            return Collections.emptyList();
        }
    }

    /**
     * Belirtilen URL'den HTML içeriğini çeker.
     *
     * <p>Kimlik doğrulama {@code Cookie: auth-token={bearerToken}} header'ı ile yapılır.</p>
     *
     * @param url hedef URL
     * @return HTML body string'i; hata durumunda {@code null}
     * @throws Exception HTTP veya I/O hatası durumunda
     */
    private String fetchHtml(String url) throws Exception {
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .get()
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

        // Cookie auth
        String bearerToken = config.getBearerToken();
        if (bearerToken != null && !bearerToken.isEmpty()) {
            requestBuilder.addHeader("Cookie", "auth-token=" + bearerToken);
        }

        Request request = requestBuilder.build();

        try (Response response = httpClient.newCall(request).execute()) {
            int statusCode = response.code();
            if (statusCode != 200) {
                log.warn("[FonPozisyon] HTTP hatası: status={}, url={}", statusCode, url);
                return null;
            }
            return response.body() != null ? response.body().string() : null;
        }
    }

    /**
     * Fintables fon pozisyonları HTML sayfasını parse eder.
     *
     * <p>{@code table.min-w-full} içindeki {@code tbody tr} satırlarından
     * fon kodu, nominal ve ağırlık bilgilerini çıkarır.</p>
     *
     * @param html ham HTML içeriği
     * @return parse edilen fon pozisyon listesi
     */
    private List<FonPozisyon> parseHtml(String html) {
        List<FonPozisyon> result = new ArrayList<>();

        Document doc = Jsoup.parse(html);
        Element table = doc.selectFirst("table[class*=min-w-full]");
        if (table == null) {
            log.debug("[FonPozisyon] Tablo bulunamadı");
            return result;
        }

        Elements rows = table.select("tbody tr");
        for (Element row : rows) {
            try {
                FonPozisyon pozisyon = parseRow(row);
                if (pozisyon != null) {
                    result.add(pozisyon);
                }
            } catch (Exception e) {
                log.debug("[FonPozisyon] Satır parse hatası: {}", e.getMessage());
            }
        }

        return result;
    }

    /**
     * Tablo satırından fon pozisyon bilgisini çıkarır.
     *
     * <p>Fon kodu: {@code a[href*=/fonlar/]} link'inden,
     * nominal: hücre text'inden (virgüllü sayı),
     * ağırlık: hücre text'inden (ondalıklı sayı) parse edilir.</p>
     *
     * @param row tablo satırı (tr elementi)
     * @return parse edilen fon pozisyon; parse edilemezse {@code null}
     */
    private FonPozisyon parseRow(Element row) {
        Elements cells = row.select("td");
        if (cells.size() < 2) {
            return null;
        }

        // Fon kodu: link'ten çıkar
        Element fonLink = row.selectFirst("a[href*=/fonlar/]");
        String fonKodu = null;
        if (fonLink != null) {
            fonKodu = extractFonKodu(fonLink.attr("href"));
        }
        // Fallback: div.text-sm class'indan fon kodu (SC uyumlu)
        if (fonKodu == null || fonKodu.isBlank()) {
            Element codeDiv = cells.get(0).selectFirst("div.text-sm");
            if (codeDiv != null) {
                fonKodu = codeDiv.text().trim();
            }
        }
        if (fonKodu == null || fonKodu.isBlank()) {
            return null;
        }

        // SC uyumlu: cells[1] icerisinde nested div'ler — nominal (ilk div), agirlik (ikinci div)
        Elements posDivs = cells.get(1).select("div");
        long nominal = 0;
        double agirlik = 0.0;

        if (!posDivs.isEmpty()) {
            nominal = parseNominal(posDivs.get(0).text().trim());
        }
        if (posDivs.size() > 1) {
            agirlik = parseAgirlik(posDivs.get(1).text().trim());
        }

        return FonPozisyon.builder()
                .fonKodu(fonKodu)
                .nominal(nominal)
                .agirlik(agirlik)
                .build();
    }

    /**
     * Fon link'inden fon kodunu çıkarır.
     *
     * <p>Beklenen format: {@code /fonlar/TTKAK} veya {@code /fonlar/TTKAK/}</p>
     *
     * @param href fon link'i
     * @return fon kodu; parse edilemezse {@code null}
     */
    private String extractFonKodu(String href) {
        if (href == null) return null;
        // /fonlar/TTKAK veya /fonlar/TTKAK/
        String[] parts = href.split("/");
        for (int i = 0; i < parts.length; i++) {
            if ("fonlar".equals(parts[i]) && i + 1 < parts.length) {
                String kod = parts[i + 1].trim();
                return kod.isEmpty() ? null : kod;
            }
        }
        return null;
    }

    /**
     * Virgüllü sayı formatını long'a parse eder.
     *
     * <p>Beklenen format: "4,910,000" → 4910000</p>
     *
     * @param text virgüllü sayı string'i
     * @return parse edilen long değer; parse edilemezse 0
     */
    private long parseNominal(String text) {
        try {
            String cleaned = text.replaceAll("[,.]", "").replaceAll("\\s+", "");
            return Long.parseLong(cleaned);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Ondalıklı sayı formatını double'a parse eder.
     *
     * <p>Beklenen format: "16.32" → 16.32. Yüzde işareti (%) varsa kaldırılır.</p>
     *
     * @param text ondalıklı sayı string'i
     * @return parse edilen double değer; parse edilemezse 0.0
     */
    private double parseAgirlik(String text) {
        try {
            // Türkçe format desteği: "16,32" → "16.32"
            String cleaned = text.replace("%", "").replace(",", ".").trim();
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Rate limiting uygular — son çağrıdan bu yana yeterli süre geçmemişse bekler.
     *
     * @throws InterruptedException bekleme kesilirse
     */
    private synchronized void applyRateLimit() throws InterruptedException {
        long now = System.currentTimeMillis();
        long lastCall = lastCallTime.get();
        long elapsed = now - lastCall;
        if (elapsed < RATE_LIMIT_MS && lastCall > 0) {
            long waitMs = RATE_LIMIT_MS - elapsed;
            log.debug("[FonPozisyon] Rate limit bekleniyor: {}ms", waitMs);
            Thread.sleep(waitMs);
        }
        lastCallTime.set(System.currentTimeMillis());
    }
}
