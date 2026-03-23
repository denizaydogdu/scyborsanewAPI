package com.scyborsa.api.service.kap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.config.HaberSyncConfig;
import com.scyborsa.api.config.KapNewsConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import jakarta.annotation.PostConstruct;

/**
 * Haber detay cekme servisi — TradingView story page scraping + /v3/story API.
 *
 * <p>KAP haberleri icin Jsoup ile HTML scrape, piyasa/dunya haberleri icin /v3/story JSON API kullanir.</p>
 *
 * <ul>
 *   <li>KAP haberleri: {@link #scrapeKapDetail(String)} — Jsoup ile TradingView story sayfasi scrape</li>
 *   <li>Piyasa/Dunya haberleri: {@link #fetchStoryDetail(String)} — TradingView /v3/story REST API</li>
 * </ul>
 *
 * @see com.scyborsa.api.service.job.HaberSyncJob
 * @see KapNewsConfig
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HaberDetailService {

    /** KAP haber konfigurasyonu (story base URL vb.). */
    private final KapNewsConfig kapNewsConfig;

    /** Haber sync konfigurasyonu (fetch timeout vb.). */
    private final HaberSyncConfig haberSyncConfig;

    /** JSON parse icin Jackson ObjectMapper. */
    private final ObjectMapper objectMapper;

    /** HTTP istekleri icin Java 11 HttpClient. */
    private HttpClient httpClient;

    /**
     * Haber iceriginde izin verilen guvenli HTML tag'leri icin Jsoup Safelist.
     *
     * <p>XSS saldirilarina karsi koruma saglar. Izin verilen tag'ler:
     * p, br, strong, em, b, i, ul, ol, li, table, thead, tbody, tr, th, td,
     * h1-h6, a (sadece href), span, div, img (sadece src).
     * script, style, event handler attribute'leri (onclick, onerror vb.) engellenir.</p>
     */
    private static final Safelist HABER_SAFELIST = Safelist.none()
            .addTags("p", "br", "strong", "em", "b", "i",
                    "ul", "ol", "li",
                    "table", "thead", "tbody", "tr", "th", "td",
                    "h1", "h2", "h3", "h4", "h5", "h6",
                    "a", "span", "div", "img")
            .addAttributes("a", "href")
            .addAttributes("img", "src")
            .addProtocols("a", "href", "http", "https")
            .addProtocols("img", "src", "http", "https");

    /**
     * HttpClient'i yapilandirilmis connect timeout ile olusturur.
     */
    @PostConstruct
    void init() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofMillis(haberSyncConfig.getFetchTimeout()))
                .build();
    }

    /**
     * KAP haberi icin TradingView story sayfasini scrape eder.
     *
     * <p>storyPath zaten tam URL ise direkt kullanir, relative path ise base URL ile birlestirir.</p>
     *
     * @param storyPath TradingView story tam URL veya relative path
     * @return scrape sonucu: [0]=detailContent (HTML paragraflar), [1]=originalKapUrl (nullable)
     */
    public String[] scrapeKapDetail(String storyPath) {
        try {
            // storyPath KapNewsClient.enrichResponse() tarafindan tam URL'e cevrilmis olabilir
            String url = storyPath.startsWith("http") ? storyPath
                    : kapNewsConfig.getStoryBaseUrl() + storyPath;
            log.debug("KAP haber scraping: {}", url);

            Document doc = Jsoup.connect(url)
                    .timeout(haberSyncConfig.getFetchTimeout())
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .get();

            // Extract article content
            StringBuilder content = new StringBuilder();
            Element article = doc.selectFirst("article");
            if (article == null) {
                // Fallback selectors
                article = doc.selectFirst(".tv-news-story__body");
                if (article == null) {
                    article = doc.selectFirst(".news-story-body");
                }
            }

            if (article != null) {
                // Paragraphs
                Elements paragraphs = article.select("p");
                for (Element p : paragraphs) {
                    String text = p.text().trim();
                    if (!text.isEmpty()) {
                        content.append("<p>").append(text).append("</p>\n");
                    }
                }
                // Tables
                Elements tables = article.select("table");
                for (Element table : tables) {
                    content.append(table.outerHtml()).append("\n");
                }
            }

            // Sanitize scraped content — XSS onlemi
            String safeContent = content.length() > 0
                    ? sanitizeHtml(content.toString()) : null;

            // Extract kap.org.tr link
            String kapUrl = null;
            Elements links = doc.select("a[href*=kap.org.tr]");
            if (!links.isEmpty()) {
                kapUrl = links.first().attr("href");
            }

            // URL dogrulama — sadece kap.org.tr kabul et
            if (kapUrl != null && !kapUrl.startsWith("https://kap.org.tr") && !kapUrl.startsWith("http://kap.org.tr")) {
                kapUrl = null;
            }

            return new String[]{
                    safeContent,
                    kapUrl
            };
        } catch (Exception e) {
            log.warn("KAP haber scraping hatasi [storyPath={}]: {}", storyPath, e.getMessage());
            return new String[]{null, null};
        }
    }

    /**
     * Piyasa/Dunya haberi icin TradingView /v3/story API'sinden detay ceker.
     *
     * <p>Response JSON'daki {@code shortDescription} ve {@code astDescription} alanlarini parse eder.
     * {@code astDescription} JSON AST formatindadir ve HTML'e donusturulur.</p>
     *
     * @param newsId TradingView haber kimligi
     * @return story sonucu: [0]=shortDescription, [1]=astDescription HTML donusumu
     */
    public String[] fetchStoryDetail(String newsId) {
        try {
            String url = "https://news-headlines.tradingview.com/v3/story?id="
                    + URLEncoder.encode(newsId, StandardCharsets.UTF_8) + "&lang=tr";
            log.debug("Story API cagrisi: {}", url);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(haberSyncConfig.getFetchTimeout()))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Story API {} dondu [newsId={}]", response.statusCode(), newsId);
                return new String[]{null, null};
            }

            JsonNode root = objectMapper.readTree(response.body());
            String shortDesc = root.has("shortDescription") && !root.get("shortDescription").isNull()
                    ? Jsoup.clean(root.get("shortDescription").asText(), Safelist.none()) : null;

            // title — on-demand olusturulan haberler icin baslik bilgisi
            String title = root.has("title") && !root.get("title").isNull()
                    ? Jsoup.clean(root.get("title").asText(), Safelist.none()) : null;

            // astDescription is a JSON AST — convert to readable HTML
            String astContent = null;
            if (root.has("astDescription") && !root.get("astDescription").isNull()) {
                astContent = convertAstToHtml(root.get("astDescription"));
            }

            // Sanitize AST-converted HTML — XSS onlemi
            String safeAstContent = astContent != null
                    ? sanitizeHtml(astContent) : null;

            return new String[]{shortDesc, safeAstContent, title};
        } catch (Exception e) {
            log.warn("Story API hatasi [newsId={}]: {}", newsId, e.getMessage());
            return new String[]{null, null};
        }
    }

    /**
     * Dis kaynaklardan gelen HTML icerigini XSS saldirilarina karsi temizler.
     *
     * <p>{@link #HABER_SAFELIST} ile tanimlanmis guvenli tag ve attribute'ler
     * disindaki tum HTML icerigini (script, style, event handler'lar vb.) siler.
     * Haber iceriginin veritabanina yazilmasindan ONCE cagrilmalidir.</p>
     *
     * @param html temizlenecek ham HTML icerigi
     * @return guvenli HTML icerigi (izin verilmeyen tag/attribute'ler cikarilmis)
     */
    private String sanitizeHtml(String html) {
        if (html == null || html.isEmpty()) return html;
        return Jsoup.clean(html, HABER_SAFELIST);
    }

    /**
     * Düz metni HTML paragraflarına dönüştürür.
     * <p>Çift newline paragraf ayırıcı, tek newline satır sonu olarak işlenir.</p>
     *
     * @param text düz metin
     * @return HTML paragrafları
     */
    private String textToHtml(String text) {
        if (text == null || text.isEmpty()) return "";
        if (!text.contains("\n")) return Jsoup.clean(text, Safelist.none());

        String[] paragraphs = text.split("\n\n+");
        StringBuilder sb = new StringBuilder();
        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (!trimmed.isEmpty()) {
                String escaped = Jsoup.clean(trimmed, Safelist.none());
                sb.append("<p>").append(escaped.replace("\n", "<br/>")).append("</p>\n");
            }
        }
        return sb.toString();
    }

    /**
     * TradingView AST (Abstract Syntax Tree) JSON'ini HTML'e donusturur.
     *
     * @param astNode AST JSON dugumu
     * @return HTML string veya {@code null}
     */
    private String convertAstToHtml(JsonNode astNode) {
        if (astNode == null || astNode.isNull()) return null;

        StringBuilder html = new StringBuilder();
        if (astNode.isArray()) {
            for (JsonNode child : astNode) {
                html.append(convertAstNodeToHtml(child));
            }
        } else {
            html.append(convertAstNodeToHtml(astNode));
        }
        return html.length() > 0 ? html.toString() : null;
    }

    /**
     * Tek AST dugumunu HTML'e donusturur (recursive).
     *
     * @param node AST dugumu
     * @return HTML string
     */
    private String convertAstNodeToHtml(JsonNode node) {
        if (node == null || node.isNull()) return "";
        if (node.isTextual()) return textToHtml(node.asText());

        if (node.isObject()) {
            String type = node.has("type") ? node.get("type").asText() : "";
            JsonNode children = node.get("children");

            StringBuilder childHtml = new StringBuilder();
            if (children != null && children.isArray()) {
                for (JsonNode child : children) {
                    childHtml.append(convertAstNodeToHtml(child));
                }
            }

            return switch (type) {
                case "paragraph" -> "<p>" + childHtml + "</p>\n";
                case "heading" -> "<h3>" + childHtml + "</h3>\n";
                case "bold", "strong" -> "<strong>" + childHtml + "</strong>";
                case "italic", "em" -> "<em>" + childHtml + "</em>";
                case "link" -> {
                    String href = node.has("href") ? node.get("href").asText() : "#";
                    if (href != null && !href.startsWith("http://") && !href.startsWith("https://")) {
                        href = "#";
                    }
                    yield "<a href=\"" + href + "\" target=\"_blank\">" + childHtml + "</a>";
                }
                case "list" -> "<ul>" + childHtml + "</ul>\n";
                case "listItem" -> "<li>" + childHtml + "</li>\n";
                case "br" -> "<br/>";
                default -> childHtml.toString();
            };
        }
        return "";
    }
}
