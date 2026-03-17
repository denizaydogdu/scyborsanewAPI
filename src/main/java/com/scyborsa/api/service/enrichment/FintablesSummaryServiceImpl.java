package com.scyborsa.api.service.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.dto.enrichment.FintablesSummaryDTO;
import com.scyborsa.api.dto.enrichment.FintablesSummaryDTO.YieldData;
import com.scyborsa.api.service.client.FintablesApiClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Fintables hisse özet bilgisi servis implementasyonu.
 *
 * <p>Fintables API'nin {@code /mobile/symbols/{STOCK}/summary/} endpoint'inden
 * sektör ve 1 yıllık getiri bilgisini çekerek {@link FintablesSummaryDTO} olarak döner.</p>
 *
 * <p>Rate limiting: çağrılar arasında minimum 1000ms bekleme süresi uygulanır.</p>
 *
 * <p>Tüm hatalar yakalanır, {@code null} döner — graceful degradation.</p>
 *
 * @see FintablesApiClient
 * @see FintablesSummaryDTO
 */
@Slf4j
@Service
public class FintablesSummaryServiceImpl implements FintablesSummaryService {

    /** Fintables API istemcisi. */
    private final FintablesApiClient fintablesApiClient;

    /** JSON parse için ObjectMapper. */
    private final ObjectMapper objectMapper;

    /** Rate limit: son çağrı zamanı (epoch millis). */
    private final AtomicLong lastCallTime = new AtomicLong(0);

    /** Rate limit: çağrılar arası minimum bekleme süresi (ms). */
    private static final long RATE_LIMIT_MS = 1000L;

    /**
     * Constructor injection ile bağımlılıkları alır.
     *
     * @param fintablesApiClient Fintables API istemcisi
     * @param objectMapper       JSON parse için ObjectMapper
     */
    public FintablesSummaryServiceImpl(FintablesApiClient fintablesApiClient,
                                       ObjectMapper objectMapper) {
        this.fintablesApiClient = fintablesApiClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Hissenin sektör ve getiri özet bilgilerini getirir.
     *
     * <p>Fintables {@code /mobile/symbols/{STOCK}/summary/} endpoint'inden
     * sektör adı ve 1 yıllık getiri verisini çeker.</p>
     *
     * @param stockName hisse kodu (ör: "GARAN")
     * @return özet bilgi; hata durumunda {@code null}
     */
    @Override
    public FintablesSummaryDTO getStockSummary(String stockName) {
        try {
            applyRateLimit();

            String url = "/mobile/symbols/" + stockName + "/summary/";
            String body = fintablesApiClient.get(url);

            if (body == null || body.isBlank()) {
                log.warn("[FintablesSummary] Boş response: stockName={}", stockName);
                return null;
            }

            JsonNode root = objectMapper.readTree(body);
            return parseSummary(root);
        } catch (Exception e) {
            log.error("[FintablesSummary] Özet bilgi alınırken hata: stockName={}", stockName, e);
            return null;
        }
    }

    /**
     * JSON response'u {@link FintablesSummaryDTO} nesnesine dönüştürür.
     *
     * <p>Sektör bilgisi {@code sectors[0].title} alanından,
     * getiri bilgisi {@code yield.data.1y} alanından okunur.</p>
     *
     * @param root JSON kök düğümü
     * @return doldurulmuş DTO; parse edilemezse {@code null}
     */
    private FintablesSummaryDTO parseSummary(JsonNode root) {
        FintablesSummaryDTO.FintablesSummaryDTOBuilder builder = FintablesSummaryDTO.builder();

        // Sektör adı: sectors[0].title
        JsonNode sectorsNode = root.path("sectors");
        if (sectorsNode.isArray() && !sectorsNode.isEmpty()) {
            JsonNode firstSector = sectorsNode.get(0);
            String sektorTitle = firstSector.path("title").asText(null);
            builder.sektorTitle(sektorTitle);
        }

        // 1 yıllık getiri: yield.data.1y
        JsonNode yieldNode = root.path("yield").path("data").path("1y");
        if (!yieldNode.isMissingNode() && yieldNode.isObject()) {
            YieldData yieldData = YieldData.builder()
                    .first(parseDouble(yieldNode, "first"))
                    .low(parseDouble(yieldNode, "low"))
                    .high(parseDouble(yieldNode, "high"))
                    .build();
            builder.yield1y(yieldData);
        }

        return builder.build();
    }

    /**
     * JSON düğümünden Double değer okur.
     *
     * @param node      JSON düğümü
     * @param fieldName alan adı
     * @return Double değer; alan yoksa veya null ise {@code null}
     */
    private Double parseDouble(JsonNode node, String fieldName) {
        JsonNode field = node.path(fieldName);
        if (field.isMissingNode() || field.isNull()) {
            return null;
        }
        return field.asDouble();
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
            log.debug("[FintablesSummary] Rate limit bekleniyor: {}ms", waitMs);
            Thread.sleep(waitMs);
        }
        lastCallTime.set(System.currentTimeMillis());
    }
}
