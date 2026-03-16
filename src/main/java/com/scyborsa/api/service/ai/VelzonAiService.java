package com.scyborsa.api.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.config.VelzonAiConfig;
import com.scyborsa.api.dto.ai.TechnicalDataDTO;
import com.scyborsa.api.dto.enrichment.FonPozisyon;
import com.scyborsa.api.dto.enrichment.StockBrokerInfo;
import com.scyborsa.api.dto.screener.ScanBodyDefinition;
import com.scyborsa.api.dto.screener.TvScreenerResponse;
import com.scyborsa.api.service.enrichment.FintablesFonPozisyonService;
import com.scyborsa.api.service.enrichment.PerStockAKDService;
import com.scyborsa.api.service.screener.TradingViewScreenerClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Velzon AI analiz servisi.
 *
 * <p>Velzon AI API uzerinden hisse teknik analiz yorumu uretir.
 * TradingView Scanner'dan 86 alan teknik veri, Fintables'ten fon pozisyonlari
 * ve AKD kurum dagitimi ile zenginlestirilmis prompt olusturur.</p>
 *
 * <p><b>Graceful degradation:</b> Tum zenginlestirme servisleri opsiyoneldir
 * ({@code @Autowired(required=false)}). Herhangi biri basarisiz olursa
 * ilgili bolum atlanir, AI yorumu yine de uretilir (ADR-017).</p>
 *
 * <p><b>Rate limit:</b> Istekler arasi minimum bekleme suresi
 * {@link VelzonAiConfig#getRateLimit()} ile yapillandirilir (default 1000ms).</p>
 *
 * @see VelzonAiConfig
 * @see TechnicalDataDTO
 * @see com.scyborsa.api.controller.AiController
 */
@Slf4j
@Service
public class VelzonAiService {

    /** AI sistem prompt'u — Turk borsasi uzman rolunde teknik analiz yorumcusu. */
    private static final String SYSTEM_PROMPT =
            "Sen bir Türk borsası uzmanısın. Verilen hisse bilgilerini analiz edip kısa (2-3 cümle) " +
            "genel değerlendirme yap. Türkçe yaz, emoji kullanma. Yatırım tavsiyesi verme, " +
            "sadece teknik analiz ve piyasa durumu hakkında yorum yap.";

    /** Velzon AI yapilandirma ayarlari. */
    private final VelzonAiConfig config;

    /** JSON serialization/deserialization. */
    private final ObjectMapper objectMapper;

    /** Java 11 HTTP istemcisi (ADR-013 pattern). */
    private final HttpClient httpClient;

    /** Son API istegi zamani (rate limit icin). */
    private final AtomicLong lastRequestTime = new AtomicLong(0);

    /** Rate limit senkronizasyon kilit nesnesi. */
    private final Object rateLimitLock = new Object();

    /** AI tek hisse scan body template'i (PLACEHOLDER runtime'da replace edilir). */
    private String aiScanBodyTemplate;

    /** Fon pozisyon servisi (opsiyonel — yoksa fon verisi atlanir). */
    @Autowired(required = false)
    private FintablesFonPozisyonService fintablesFonPozisyonService;

    /** AKD kurum dagitim servisi (opsiyonel — yoksa AKD verisi atlanir). */
    @Autowired(required = false)
    private PerStockAKDService perStockAKDService;

    /** TradingView screener istemcisi (opsiyonel — yoksa teknik veri atlanir). */
    @Autowired(required = false)
    private TradingViewScreenerClient tradingViewScreenerClient;

    /**
     * Constructor — config ve objectMapper zorunlu bagimliliklari alir.
     *
     * @param config Velzon AI yapilandirmasi
     * @param objectMapper Jackson ObjectMapper
     */
    public VelzonAiService(VelzonAiConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getTimeout()))
                .build();
    }

    /**
     * Startup'ta AI scan body template'ini yukler.
     *
     * <p>classpath:screener-bodies/ai-tek-hisse-scan.json dosyasini okur.
     * Yuklenemezse teknik veri olmadan devam edilir (graceful degradation).</p>
     */
    @PostConstruct
    public void init() {
        try {
            ClassPathResource resource = new ClassPathResource("screener-bodies/ai-tek-hisse-scan.json");
            try (InputStream is = resource.getInputStream()) {
                this.aiScanBodyTemplate = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            log.info("[AI-SERVICE] Scan body template yuklendi ({} karakter)", aiScanBodyTemplate.length());
        } catch (Exception e) {
            log.error("[AI-SERVICE] Scan body template yuklenemedi: {}", e.getMessage());
            this.aiScanBodyTemplate = null;
        }
    }

    /**
     * AI servisi aktif mi kontrol eder.
     *
     * @return servis aktifse true
     */
    public boolean isEnabled() {
        return config.isEnabled();
    }

    /**
     * Hisse analizi yapar ve AI yorumu dondurur.
     *
     * <p>Islem sirasi:</p>
     * <ol>
     *   <li>Rate limit kontrolu</li>
     *   <li>TradingView'dan 86 alan teknik veri cekme (opsiyonel)</li>
     *   <li>Fintables'ten fon pozisyonlari cekme (opsiyonel)</li>
     *   <li>AKD kurum dagitimi cekme (opsiyonel)</li>
     *   <li>Zenginlestirilmis prompt olusturma</li>
     *   <li>Velzon AI API cagrisi</li>
     * </ol>
     *
     * @param stockCode hisse kodu (orn. GARAN)
     * @param price mevcut fiyat (opsiyonel — null ise prompt'a eklenmez)
     * @param changePercent degisim yuzdesi (opsiyonel — null ise prompt'a eklenmez)
     * @param screenerNames ciktigi tarama isimleri (opsiyonel — bos liste verilebilir)
     * @return AI yorumu veya null (hata/devre disi durumunda)
     */
    public String analyzeStock(String stockCode, Double price, Double changePercent, List<String> screenerNames) {
        if (!config.isEnabled()) {
            log.debug("[AI-SERVICE] Velzon AI devre disi");
            return null;
        }

        if (stockCode == null || !stockCode.matches("^[A-Z0-9]{2,6}$")) {
            log.warn("[AI-SERVICE] Gecersiz hisse kodu formati: {}", stockCode);
            return null;
        }

        try {
            enforceRateLimit();

            // Teknik veri cek (86 alan — opsiyonel)
            TechnicalDataDTO technicalData = getTechnicalData(stockCode);

            // Fon pozisyonlari cek (opsiyonel)
            String fonPozisyonlari = getFonPozisyonlariText(stockCode);

            // AKD kurum dagitimi cek (opsiyonel)
            String akdBilgileri = getAkdBilgileriText(stockCode);

            // User message olustur (zenginlestirilmis)
            String userMessage = buildEnrichedUserMessage(stockCode, price, changePercent,
                    screenerNames, technicalData, fonPozisyonlari, akdBilgileri);

            log.info("[AI-SERVICE] Velzon AI analiz basliyor: {} | Fiyat: {} | Degisim: {}% | Teknik veri: {}",
                    stockCode, price, changePercent, technicalData != null ? "VAR" : "YOK");

            // API cagrisi yap
            String aiResponse = callVelzonAi(userMessage);

            if (aiResponse != null && !aiResponse.isEmpty()) {
                log.info("[AI-SERVICE] Velzon AI yaniti alindi: {} | {} karakter", stockCode, aiResponse.length());
                return aiResponse;
            }

            return null;

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("[AI-SERVICE] Rate limit beklemesi kesildi ({})", stockCode);
            return null;
        } catch (Exception e) {
            log.error("[AI-SERVICE] Velzon AI analiz hatasi ({}): {}", stockCode, e.getMessage());
            return null;  // Graceful degradation — NEVER throw
        }
    }

    // ==================== PRIVATE METHODS ====================

    /**
     * Velzon AI API'ye HTTP POST istegi gonderir.
     *
     * @param userMessage kullanici mesaji (zenginlestirilmis prompt)
     * @return AI yanit metni veya null
     */
    private String callVelzonAi(String userMessage) {
        try {
            String requestBody = objectMapper.writeValueAsString(new VelzonAiRequest(
                    SYSTEM_PROMPT, userMessage, config.getMaxTokens(), config.getTemperature()));

            log.debug("[AI-SERVICE] Velzon AI Request: {}",
                    requestBody.substring(0, Math.min(200, requestBody.length())));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getUrl()))
                    .timeout(Duration.ofSeconds(config.getTimeout()))
                    .header("X-API-KEY", config.getApiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401 || response.statusCode() == 403) {
                log.warn("[AI-SERVICE] Velzon AI API Auth hatasi: {}", response.statusCode());
                return null;
            }

            if (response.statusCode() != 200) {
                log.warn("[AI-SERVICE] Velzon AI API HTTP {}", response.statusCode());
                return null;
            }

            String responseBody = response.body();
            log.debug("[AI-SERVICE] Velzon AI Response: {}",
                    responseBody.substring(0, Math.min(300, responseBody.length())));

            return parseResponse(responseBody);

        } catch (Exception e) {
            log.error("[AI-SERVICE] Velzon AI API cagri hatasi: {}", e.getMessage());
            return null;
        }
    }

    /**
     * API response'u parse eder.
     *
     * <p>Once "response" alanina, bulunamazsa "data" alanina bakar.
     * success=false durumunda null dondurur.</p>
     *
     * @param responseBody HTTP response body
     * @return AI yorum metni veya null
     */
    private String parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // success kontrolu
            JsonNode successNode = root.get("success");
            if (successNode != null && !successNode.asBoolean(false)) {
                log.warn("[AI-SERVICE] Velzon AI success=false");
                return null;
            }

            // response alanini al
            JsonNode responseNode = root.get("response");
            if (responseNode != null && !responseNode.isNull()) {
                return responseNode.asText("");
            }

            // Fallback: data alani
            JsonNode dataNode = root.get("data");
            if (dataNode != null && !dataNode.isNull()) {
                return dataNode.asText("");
            }

            return null;

        } catch (Exception e) {
            log.error("[AI-SERVICE] Velzon AI response parse hatasi: {}", e.getMessage());
            return null;
        }
    }

    /**
     * TradingView Scanner API'den tek hisse icin 86 alan teknik veri ceker.
     *
     * @param stockCode hisse kodu
     * @return TechnicalDataDTO veya null (servis/template yoksa)
     */
    private TechnicalDataDTO getTechnicalData(String stockCode) {
        if (tradingViewScreenerClient == null || aiScanBodyTemplate == null) {
            return null;
        }

        try {
            // Template'deki PLACEHOLDER'i hisse koduyla degistir
            String scanBodyJson = aiScanBodyTemplate.replace("PLACEHOLDER", stockCode);
            ScanBodyDefinition scanBody = new ScanBodyDefinition("ai-tek-hisse-" + stockCode, scanBodyJson);

            TvScreenerResponse response = tradingViewScreenerClient.executeScan(scanBody);
            if (response != null && response.getData() != null && !response.getData().isEmpty()) {
                return TechnicalDataDTO.fromTvResponse(response);
            }
        } catch (Exception e) {
            log.debug("[AI-SERVICE] Teknik veri alinamadi ({}): {}", stockCode, e.getMessage());
        }
        return null;
    }

    /**
     * Zenginlestirilmis user message olusturur.
     *
     * <p>Temel bilgiler, teknik analiz, mum formasyonlari, pivot noktalar,
     * temel analiz, kurumsal veriler ve fon pozisyonlarini icerir.</p>
     *
     * @param stockCode hisse kodu
     * @param price mevcut fiyat (opsiyonel)
     * @param changePercent degisim yuzdesi (opsiyonel)
     * @param screenerNames ciktigi tarama isimleri (opsiyonel)
     * @param technicalData teknik veri DTO'su (opsiyonel)
     * @param fonPozisyonlari fon pozisyonlari text (opsiyonel)
     * @param akdBilgileri AKD kurum dagitimi text (opsiyonel)
     * @return AI prompt icin user message
     */
    private String buildEnrichedUserMessage(String stockCode, Double price, Double changePercent,
                                             List<String> screenerNames, TechnicalDataDTO technicalData,
                                             String fonPozisyonlari, String akdBilgileri) {
        StringBuilder sb = new StringBuilder();

        sb.append("Hisse Analizi: ").append(stockCode).append("\n\n");

        // Temel bilgiler
        sb.append("TEMEL BILGILER:\n");
        if (price != null && price > 0) {
            sb.append("- Fiyat: ").append(String.format("%.2f", price)).append(" TL\n");
        }
        if (changePercent != null) {
            String prefix = changePercent >= 0 ? "+" : "";
            sb.append("- Degisim: ").append(prefix).append(String.format("%.2f", changePercent)).append("%\n");
        }

        // Hacim ve piyasa degeri (teknik veriden)
        if (technicalData != null) {
            if (technicalData.getVolume() != null && technicalData.getVolume() > 0) {
                sb.append("- Hacim: ").append(formatVolume(technicalData.getVolume())).append(" lot\n");
            }
            if (technicalData.getMarketCap() != null && technicalData.getMarketCap() > 0) {
                sb.append("- Piyasa Degeri: ").append(formatMarketCap(technicalData.getMarketCap())).append("\n");
            }
        }

        if (screenerNames != null && !screenerNames.isEmpty()) {
            // Screener isimleri external veri — prompt injection korumasi
            String sanitized = screenerNames.stream()
                    .filter(n -> n != null && n.length() <= 100)
                    .map(n -> n.replaceAll("[^\\p{L}\\p{N}\\s\\-_/()]", ""))
                    .collect(Collectors.joining(", "));
            if (!sanitized.isEmpty()) {
                sb.append("- Ciktigi Taramalar: ").append(sanitized).append("\n");
            }
        }

        // Teknik analiz (varsa)
        if (technicalData != null) {
            sb.append(technicalData.toTechnicalPromptSection());
            sb.append(technicalData.toCandlePatternPromptSection());
            sb.append(technicalData.toPivotPromptSection());
            sb.append(technicalData.toFundamentalPromptSection());
        }

        // Kurumsal veriler
        if (akdBilgileri != null && !akdBilgileri.isEmpty()) {
            sb.append("\nKURUMSAL VERILER:\n");
            sb.append(akdBilgileri).append("\n");
        }

        // Fon pozisyonlari
        if (fonPozisyonlari != null && !fonPozisyonlari.isEmpty()) {
            sb.append("\nFON POZISYONLARI:\n");
            sb.append(fonPozisyonlari).append("\n");
        }

        return sb.toString();
    }

    /**
     * Fon pozisyonlarini text olarak alir (top 5).
     *
     * @param stockCode hisse kodu
     * @return formatli fon pozisyonlari text veya null
     */
    private String getFonPozisyonlariText(String stockCode) {
        if (fintablesFonPozisyonService == null) {
            return null;
        }

        try {
            List<FonPozisyon> pozisyonlar = fintablesFonPozisyonService.getFonPozisyonlari(stockCode);
            if (pozisyonlar == null || pozisyonlar.isEmpty()) {
                return null;
            }

            // Ilk 5 fonu al ve formatla
            return pozisyonlar.stream()
                    .limit(5)
                    .map(fp -> String.format("- %s: %d lot (%%%.2f)", fp.getFonKodu(), fp.getNominal(), fp.getAgirlik()))
                    .collect(Collectors.joining("\n"));

        } catch (Exception e) {
            log.debug("[AI-SERVICE] Fon pozisyonlari alinamadi ({}): {}", stockCode, e.getMessage());
            return null;
        }
    }

    /**
     * AKD kurum dagitimini text olarak alir.
     *
     * @param stockCode hisse kodu
     * @return formatli AKD kurum dagitimi text veya null
     */
    private String getAkdBilgileriText(String stockCode) {
        if (perStockAKDService == null) {
            return null;
        }

        try {
            List<StockBrokerInfo> brokers = perStockAKDService.getStockBrokerDistribution(stockCode);
            if (brokers == null || brokers.isEmpty()) {
                return null;
            }

            StringBuilder sb = new StringBuilder();
            for (StockBrokerInfo broker : brokers) {
                sb.append(String.format("%s %s: %s\n",
                        broker.getEmoji() != null ? broker.getEmoji() : "-",
                        broker.getBrokerName(),
                        broker.getFormattedVolume() != null ? broker.getFormattedVolume() : "N/A"));
            }

            return sb.toString().trim();

        } catch (Exception e) {
            log.debug("[AI-SERVICE] AKD bilgileri alinamadi ({}): {}", stockCode, e.getMessage());
            return null;
        }
    }

    /**
     * Hacmi Turkce formatlar (proje kurali: B/M/K DEGIL — Milyar/Milyon/Bin).
     *
     * @param volume hacim degeri
     * @return Turkce formatli hacim metni
     */
    private String formatVolume(Long volume) {
        if (volume == null) return "0";
        if (volume >= 1_000_000_000L) {
            return String.format("%.1f Milyar", volume / 1_000_000_000.0);
        }
        if (volume >= 1_000_000L) {
            return String.format("%.1f Milyon", volume / 1_000_000.0);
        }
        if (volume >= 1_000L) {
            return String.format("%.1f Bin", volume / 1_000.0);
        }
        return String.valueOf(volume);
    }

    /**
     * Piyasa degerini Turkce formatlar.
     *
     * @param cap piyasa degeri (TL)
     * @return Turkce formatli piyasa degeri metni
     */
    private String formatMarketCap(Long cap) {
        if (cap == null) return "0";
        if (cap >= 1_000_000_000_000L) {
            return String.format("%.1f Trilyon TL", cap / 1_000_000_000_000.0);
        }
        if (cap >= 1_000_000_000L) {
            return String.format("%.1f Milyar TL", cap / 1_000_000_000.0);
        }
        if (cap >= 1_000_000L) {
            return String.format("%.1f Milyon TL", cap / 1_000_000.0);
        }
        return cap + " TL";
    }

    /**
     * Rate limit uygular (thread-safe).
     *
     * <p>Son istekten bu yana gecen sure, yapilandirilan minimum
     * bekleme suresinden azsa fark kadar bekler.</p>
     *
     * @throws InterruptedException bekleme sirasinda interrupt olursa
     */
    private void enforceRateLimit() throws InterruptedException {
        synchronized (rateLimitLock) {
            long elapsed = System.currentTimeMillis() - lastRequestTime.get();
            if (elapsed < config.getRateLimit()) {
                Thread.sleep(config.getRateLimit() - elapsed);
            }
            lastRequestTime.set(System.currentTimeMillis());
        }
    }

    /**
     * Velzon AI API istek DTO'su.
     *
     * @param systemPrompt sistem prompt'u (AI rolunu tanimlar)
     * @param userMessage kullanici mesaji (hisse verisi + prompt)
     * @param maxTokens maksimum uretilecek token
     * @param temperature model temperature (0.0-1.0)
     */
    private record VelzonAiRequest(String systemPrompt, String userMessage, int maxTokens, double temperature) {}
}
