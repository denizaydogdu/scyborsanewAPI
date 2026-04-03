package com.scyborsa.api.service.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.config.FintablesApiConfig;
import com.scyborsa.api.dto.enrichment.FonPozisyon;
import com.scyborsa.api.service.client.FintablesMcpClient;
import com.scyborsa.api.service.client.FintablesMcpTokenStore;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fintables fon pozisyon servis implementasyonu.
 *
 * <p>Fintables MCP SQL sorgusunu birincil kaynak olarak kullanır.
 * MCP başarısız olursa veya token geçersizse Fintables web sitesinden
 * ({@code /sirketler/{STOCK}/fon-pozisyonlari}) HTML scraping ile
 * fonların hisse pozisyon bilgilerini çeker (fallback).</p>
 *
 * <p>Veri kaynağı: Fintables MCP {@code fon_portfoy_dagilim_raporu_sembol_agirliklari} tablosu.</p>
 *
 * <p>Kimlik doğrulama (scraping fallback): {@code Cookie: auth-token={bearerToken}} header'ı.</p>
 *
 * <p>Rate limiting (scraping fallback): çağrılar arasında minimum 3500ms bekleme süresi.</p>
 *
 * <p>Tüm hatalar yakalanır, boş liste döner — graceful degradation.</p>
 *
 * @see FintablesApiConfig
 * @see FintablesMcpClient
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

    /** Fintables MCP istemcisi (opsiyonel — graceful degradation). */
    @Autowired(required = false)
    private FintablesMcpClient mcpClient;

    /** Fintables MCP token saklama bileşeni (opsiyonel — graceful degradation). */
    @Autowired(required = false)
    private FintablesMcpTokenStore tokenStore;

    /** JSON parse için ObjectMapper. */
    @Autowired
    private ObjectMapper objectMapper;

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
     * <p>Önce Fintables MCP SQL sorgusu ile veri çekmeye çalışır (birincil kaynak).
     * MCP başarısız olursa veya token geçersizse HTML scraping fallback kullanılır.</p>
     *
     * @param stockName hisse kodu (ör: "GARAN")
     * @return fon pozisyon listesi; hata durumunda boş liste
     */
    @Override
    public List<FonPozisyon> getFonPozisyonlari(String stockName) {
        // 1. Birincil kaynak: MCP SQL sorgusu
        List<FonPozisyon> mcpResult = tryMcpQuery(stockName);
        if (mcpResult != null && !mcpResult.isEmpty()) {
            return mcpResult;
        }

        // 2. Fallback: HTML scraping
        return tryHtmlScraping(stockName);
    }

    /**
     * Fintables MCP üzerinden fon pozisyon verilerini SQL sorgusuyla çeker.
     *
     * <p>MCP istemcisi veya token mevcut değilse veya geçersizse null döndürür.
     * Sorgu: {@code fon_portfoy_dagilim_raporu_sembol_agirliklari} tablosundan
     * belirtilen hisse kodunun fon pozisyonlarını ağırlık sırasına göre çeker.</p>
     *
     * @param stockName hisse kodu (ör: "GARAN")
     * @return fon pozisyon listesi; MCP kullanılamıyorsa veya hata durumunda null
     */
    private List<FonPozisyon> tryMcpQuery(String stockName) {
        if (mcpClient == null || tokenStore == null || !tokenStore.isTokenValid()) {
            log.debug("[FON-POZISYON] MCP kullanılamıyor, scraping fallback kullanılacak: stockName={}", stockName);
            return null;
        }

        // SQL injection koruması: sadece büyük harf ve rakam kabul et
        String safeName = stockName != null ? stockName.trim().toUpperCase() : "";
        if (!safeName.matches("^[A-Z0-9]{1,10}$")) {
            log.warn("[FON-POZISYON] Geçersiz sembol formatı: {}", stockName);
            return null;
        }

        try {
            String sql = "SELECT fon_kodu, fondaki_lot as nominal, yuzdesel_agirlik " +
                    "FROM fon_portfoy_dagilim_raporu_sembol_agirliklari " +
                    "WHERE fon_kodu = '" + safeName + "' " +
                    "ORDER BY yuzdesel_agirlik DESC LIMIT 20";

            JsonNode result = mcpClient.veriSorgula(sql, "Fon pozisyonları: " + stockName);
            if (result == null) {
                log.debug("[FON-POZISYON] MCP boş yanıt döndü: stockName={}", stockName);
                return null;
            }

            String responseText = extractResponseText(result);
            if (responseText == null || responseText.isBlank()) {
                log.debug("[FON-POZISYON] MCP response text boş: stockName={}", stockName);
                return null;
            }

            List<FonPozisyon> parsed = parseMcpMarkdownTable(responseText);
            if (!parsed.isEmpty()) {
                log.info("[FON-POZISYON] MCP'den veri alındı: {} fon, stockName={}", parsed.size(), stockName);
                return parsed;
            }

            return null;
        } catch (Exception e) {
            log.warn("[FON-POZISYON] MCP başarısız, HTML scraping fallback: stockName={}, hata={}", stockName, e.getMessage());
            return null;
        }
    }

    /**
     * HTML scraping ile fon pozisyon verilerini çeker (fallback).
     *
     * @param stockName hisse kodu (ör: "GARAN")
     * @return fon pozisyon listesi; hata durumunda boş liste
     */
    private List<FonPozisyon> tryHtmlScraping(String stockName) {
        try {
            applyRateLimit();

            String url = FINTABLES_WEB_URL + "/sirketler/" + stockName + "/fon-pozisyonlari";
            String html = fetchHtml(url);

            if (html == null || html.isBlank()) {
                log.debug("[FON-POZISYON] Boş HTML response: stockName={}", stockName);
                return Collections.emptyList();
            }

            List<FonPozisyon> result = parseHtml(html);
            log.debug("[FON-POZISYON] HTML scraping {} fon pozisyonu döndü: stockName={}", result.size(), stockName);
            return result;
        } catch (Exception e) {
            log.error("[FON-POZISYON] Fon pozisyonları alınırken hata: stockName={}", stockName, e);
            return Collections.emptyList();
        }
    }

    /**
     * MCP JSON-RPC result nesnesinden metin yanıtını çıkarır.
     *
     * @param result JSON-RPC result alanı
     * @return metin yanıtı veya null
     */
    private String extractResponseText(JsonNode result) {
        if (result == null || objectMapper == null) {
            return null;
        }
        try {
            JsonNode content = result.get("content");
            if (content != null && content.isArray() && !content.isEmpty()) {
                JsonNode firstContent = content.get(0);
                if (firstContent.has("text")) {
                    return firstContent.get("text").asText();
                }
            }
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("[FON-POZISYON] Response text çıkarma hatası", e);
            return null;
        }
    }

    /**
     * MCP markdown tablo yanıtını parse ederek FonPozisyon listesine dönüştürür.
     *
     * <p>Beklenen format:
     * <pre>
     * | fon_kodu | nominal | yuzdesel_agirlik |
     * |---|---|---|
     * | TTKAK | 4910000 | 16.32 |
     * </pre>
     * </p>
     *
     * @param rawText MCP yanıt metni (markdown tablo)
     * @return parse edilmiş fon pozisyon listesi
     */
    private List<FonPozisyon> parseMcpMarkdownTable(String rawText) {
        List<FonPozisyon> result = new ArrayList<>();
        if (rawText == null || rawText.isBlank()) {
            return result;
        }

        try {
            // JSON sarmalayıcısını çöz
            String tableText = rawText;
            if (objectMapper != null && rawText.trim().startsWith("{")) {
                JsonNode json = objectMapper.readTree(rawText);
                if (json.has("table")) {
                    tableText = json.get("table").asText();
                } else if (json.has("content") && json.get("content").isArray()) {
                    JsonNode content = json.get("content");
                    if (!content.isEmpty() && content.get(0).has("text")) {
                        tableText = content.get(0).get("text").asText();
                        if (tableText.trim().startsWith("{")) {
                            JsonNode innerJson = objectMapper.readTree(tableText);
                            if (innerJson.has("table")) {
                                tableText = innerJson.get("table").asText();
                            }
                        }
                    }
                }
            }

            String[] lines = tableText.split("\n");
            if (lines.length < 3) {
                return result;
            }

            // Header satırından kolon indekslerini bul
            String[] headers = parseMdRow(lines[0]);
            int idxFonKodu = findMdColumnIndex(headers, "fon_kodu");
            int idxNominal = findMdColumnIndex(headers, "nominal", "fondaki_lot");
            int idxAgirlik = findMdColumnIndex(headers, "yuzdesel_agirlik");

            // Satır 0 = header, satır 1 = ayırıcı, satır 2+ = veri
            for (int i = 2; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty() || line.replaceAll("[|:\\-\\s]", "").isEmpty()) {
                    continue;
                }

                String[] cols = parseMdRow(line);
                if (cols.length == 0) {
                    continue;
                }

                try {
                    String fonKodu = safeGetMd(cols, idxFonKodu);
                    if (fonKodu == null || fonKodu.isBlank()) {
                        continue;
                    }

                    long nominal = parseMdNominal(safeGetMd(cols, idxNominal));
                    double agirlik = parseMdAgirlik(safeGetMd(cols, idxAgirlik));

                    result.add(FonPozisyon.builder()
                            .fonKodu(fonKodu)
                            .nominal(nominal)
                            .agirlik(agirlik)
                            .build());
                } catch (Exception e) {
                    log.debug("[FON-POZISYON] MCP satır parse hatası (satır {}): {}", i, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[FON-POZISYON] MCP markdown tablo parse hatası", e);
        }

        return result;
    }

    /**
     * Markdown tablo satırını kolon dizisine dönüştürür.
     *
     * @param row markdown tablo satırı (ör: "| A | B | C |")
     * @return kolon değerleri (trim'li)
     */
    private String[] parseMdRow(String row) {
        if (row == null || !row.contains("|")) {
            return new String[0];
        }
        String trimmed = row.trim();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        String[] parts = trimmed.split("\\|");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    /**
     * Header dizisinde kolon adını arar ve indeksini döndürür.
     *
     * @param headers header dizisi
     * @param names   aranan kolon adları (herhangi biri eşleşirse döner)
     * @return kolon indeksi, bulunamazsa -1
     */
    private int findMdColumnIndex(String[] headers, String... names) {
        for (int i = 0; i < headers.length; i++) {
            for (String name : names) {
                if (headers[i].equalsIgnoreCase(name)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Güvenli dizi erişimi (markdown parse için).
     *
     * @param arr   dizi
     * @param index erişilecek indeks
     * @return değer veya null (indeks geçersizse veya null/None ise)
     */
    private String safeGetMd(String[] arr, int index) {
        if (index < 0 || index >= arr.length) {
            return null;
        }
        String val = arr[index].trim();
        return val.isEmpty() || "null".equalsIgnoreCase(val) || "None".equalsIgnoreCase(val) ? null : val;
    }

    /**
     * MCP markdown nominal değerini long'a parse eder.
     *
     * @param value nominal string değeri
     * @return long nominal; parse edilemezse 0
     */
    private long parseMdNominal(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Double.valueOf(value.replace(",", "").trim()).longValue();
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * MCP markdown ağırlık değerini double'a parse eder.
     *
     * @param value ağırlık string değeri
     * @return double ağırlık; parse edilemezse 0.0
     */
    private double parseMdAgirlik(String value) {
        if (value == null || value.isBlank()) return 0.0;
        try {
            return Double.parseDouble(value.replace(",", ".").replace("%", "").trim());
        } catch (NumberFormatException e) {
            return 0.0;
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
