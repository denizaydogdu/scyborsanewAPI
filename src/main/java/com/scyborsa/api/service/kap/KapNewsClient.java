package com.scyborsa.api.service.kap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.config.KapNewsConfig;
import com.scyborsa.api.config.TradingViewConfig;
import com.scyborsa.api.dto.kap.KapNewsItemDto;
import com.scyborsa.api.dto.kap.KapNewsResponseDto;
import com.scyborsa.api.dto.kap.KapRelatedSymbolDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * TradingView news-mediator API'sinden canlı KAP haberlerini çeken client.
 *
 * <p>Java 11 HttpClient kullanarak {@code news-mediator.tradingview.com} adresinden
 * Türkçe KAP haberlerini alır. Logo URL enrichment ve zaman formatlama işlemlerini
 * response üzerinde gerçekleştirir.</p>
 *
 * <p>Hata durumunda graceful degradation uygulanır — {@code null} döner,
 * controller boş liste ile cevap verir.</p>
 *
 * @see KapNewsConfig
 * @see KapNewsResponseDto
 */
@Slf4j
@Service
public class KapNewsClient {

    /** Istanbul saat dilimi (Europe/Istanbul). */
    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");
    /** Turkce tarih-saat formatlayici (orn: "03 Mart 2026 14:30"). */
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("dd MMMM yyyy HH:mm", new Locale("tr", "TR"));

    /** KAP news API yapilandirmasi. */
    private final KapNewsConfig config;
    /** JSON parse icin Jackson ObjectMapper. */
    private final ObjectMapper objectMapper;
    /** HTTP istekleri icin Java 11 HttpClient. */
    private final HttpClient httpClient;

    /**
     * Constructor — bağımlılıkları alır ve HTTP client oluşturur.
     *
     * @param config KAP news API yapılandırması
     * @param objectMapper JSON parse için Jackson ObjectMapper
     */
    public KapNewsClient(KapNewsConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getConnectTimeoutSeconds()))
                .build();
    }

    /**
     * TradingView news-mediator API'sinden KAP haberlerini çeker.
     *
     * <p>API çağrısı sonrası logo URL enrichment ve zaman formatlama uygulanır.</p>
     *
     * @return KAP haber response; hata durumunda {@code null}
     */
    public KapNewsResponseDto fetchKapNews() {
        return fetchNews(
                config.getApiUrl() + "?filter=lang:tr&filter=provider:kap&streaming=true",
                "KAP-NEWS");
    }

    /**
     * TradingView headlines API'sinden piyasa haberlerini çeker.
     *
     * <p>API çağrısı sonrası logo URL enrichment ve zaman formatlama uygulanır.</p>
     *
     * @return piyasa haberleri response; hata durumunda {@code null}
     */
    public KapNewsResponseDto fetchMarketNews() {
        return fetchNews(
                config.getMarketNewsUrl() + "?category=stock&client=landing&lang=tr&market_country=TR&streaming=true",
                "MARKET-NEWS");
    }

    /**
     * TradingView news-mediator API'sinden dünya haberlerini çeker.
     *
     * <p>KAP haberleriyle aynı base URL kullanılır, farklı query parametreleri ile
     * ({@code area:WLD&lang:tr}) dünya haberleri filtrelenir.</p>
     *
     * @return dünya haberleri response; hata durumunda {@code null}
     */
    public KapNewsResponseDto fetchWorldNews() {
        return fetchNews(
                config.getWorldNewsUrl() + "?filter=area:WLD&filter=lang:tr&streaming=true",
                "WORLD-NEWS");
    }

    /**
     * Verilen URL'den haber verisi çeker, enrichment uygular ve döner.
     *
     * <p>Ortak HTTP çağrı, deserialization ve enrichment mantığını barındırır.
     * Hata durumunda {@code null} döner (graceful degradation).</p>
     *
     * @param url    tam API URL'i (base + query params)
     * @param logTag log mesajlarında kullanılacak etiket (örn: "KAP-NEWS")
     * @return haber response; hata durumunda {@code null}
     */
    private KapNewsResponseDto fetchNews(String url, String logTag) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(config.getRequestTimeoutSeconds()))
                    .header("Accept", "*/*")
                    .header("Accept-Language", "en-GB,en;q=0.7")
                    .header("Origin", config.getStoryBaseUrl())
                    .header("Referer", config.getStoryBaseUrl() + "/")
                    .header("User-Agent", TradingViewConfig.DEFAULT_USER_AGENT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[{}] API hatası: status={}", logTag, response.statusCode());
                return null;
            }

            KapNewsResponseDto result = objectMapper.readValue(response.body(), KapNewsResponseDto.class);
            enrichResponse(result);
            return result;
        } catch (Exception e) {
            log.error("[{}] Haberler alınamadı", logTag, e);
            return null;
        }
    }

    /**
     * Response üzerinde logo URL ve zaman formatlama enrichment uygular.
     *
     * @param response enrichment yapılacak response
     */
    private void enrichResponse(KapNewsResponseDto response) {
        if (response == null || response.getItems() == null) {
            return;
        }
        for (KapNewsItemDto item : response.getItems()) {
            // Zaman formatlama (null guard — published yoksa "-")
            if (item.getPublished() != null) {
                item.setFormattedPublished(formatTimestamp(item.getPublished()));
            } else {
                item.setFormattedPublished("-");
            }

            // storyPath enrichment — relative path'i absolute URL'e çevir
            if (item.getStoryPath() != null && !item.getStoryPath().startsWith("http")) {
                item.setStoryPath(config.getStoryBaseUrl() + item.getStoryPath());
            }

            // Logo URL enrichment
            List<KapRelatedSymbolDto> symbols = item.getRelatedSymbols();
            if (symbols != null) {
                for (KapRelatedSymbolDto symbol : symbols) {
                    if (symbol.getLogoid() != null && !symbol.getLogoid().isBlank()) {
                        symbol.setLogoUrl(config.getLogoBaseUrl() + symbol.getLogoid() + ".svg");
                    }
                }
            }
        }
    }

    /**
     * Unix epoch saniyesini Türkiye saatine göre formatlar.
     *
     * @param epochSecond unix epoch saniye
     * @return formatlanmış tarih-saat (örn: "03 Mart 2026 14:30")
     */
    private String formatTimestamp(long epochSecond) {
        return Instant.ofEpochSecond(epochSecond)
                .atZone(ISTANBUL_ZONE)
                .format(FORMATTER);
    }
}
