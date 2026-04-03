package com.scyborsa.api.service.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.enums.EnrichmentDataTypeEnum;
import com.scyborsa.api.model.EnrichmentCache;
import com.scyborsa.api.repository.EnrichmentCacheRepository;
import com.scyborsa.api.service.client.FintablesMcpClient;
import com.scyborsa.api.service.client.FintablesMcpTokenStore;
import com.scyborsa.api.utils.BistTradingCalendar;
import com.scyborsa.api.utils.ProfileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Günlük finansal oran verilerini Fintables MCP'den çekip EnrichmentCache'e yazan scheduled job.
 *
 * <p>Her iş günü saat 22:00'de (Europe/Istanbul) çalışır.
 * Fintables MCP {@code veri_sorgula} tool'u ile {@code hisse_finansal_tablolari_finansal_oranlari}
 * tablosundan son 5000 kaydı çeker ve tek bir {@link EnrichmentCache} kaydı olarak saklar.</p>
 *
 * <p><b>Özellikler:</b></p>
 * <ul>
 *   <li>İdempotent: Aynı gün için kayıt zaten varsa tekrar yazmaz</li>
 *   <li>Guard clause: Sadece prod profilinde, iş günlerinde ve token geçerliyken çalışır</li>
 *   <li>Async: Scheduler thread'i bloklamaz, ayrı thread'de çalışır</li>
 *   <li>Piyasa geneli veri: stockCode = "_SYSTEM_" (per-hisse değil)</li>
 *   <li>Eski veri temizleme: 30 günden eski FINANSAL_ORAN kayıtları silinir</li>
 * </ul>
 *
 * @see FinansalOranService
 * @see FintablesMcpClient#veriSorgula(String, String)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FinansalOranSyncJob {

    /** Istanbul saat dilimi. */
    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");

    /** Piyasa geneli veri için kullanılan sanal hisse kodu. */
    private static final String SYSTEM_STOCK_CODE = "_SYSTEM_";

    /** Eski cache kayıtlarının temizleneceği gün eşiği. */
    private static final int CLEANUP_DAYS = 30;

    /** Fintables MCP istemcisi. */
    private final FintablesMcpClient mcpClient;

    /** Fintables MCP token saklama bileşeni. */
    private final FintablesMcpTokenStore tokenStore;

    /** Zenginleştirilmiş veri cache repository. */
    private final EnrichmentCacheRepository cacheRepository;

    /** JSON serializasyon için ObjectMapper. */
    private final ObjectMapper objectMapper;

    /** Spring profil kontrol utility. */
    private final ProfileUtils profileUtils;

    /**
     * Günlük finansal oran senkronizasyonunu tetikler.
     *
     * <p>Her iş günü 22:00'de çalışır. Prod profili, iş günü ve token geçerliliği
     * kontrolü yapar. Asıl işi {@link #doSync()} async metoduna delege eder.</p>
     */
    @Scheduled(cron = "0 0 22 * * MON-FRI", zone = "Europe/Istanbul")
    public void syncDailyData() {
        if (!profileUtils.isProdProfile()) {
            log.debug("[FINANSAL-ORAN-SYNC] Prod değil, senkronizasyon atlanıyor");
            return;
        }

        if (!BistTradingCalendar.isTradingDay()) {
            log.debug("[FINANSAL-ORAN-SYNC] Tatil günü, senkronizasyon atlanıyor");
            return;
        }

        if (!tokenStore.isTokenValid()) {
            log.warn("[FINANSAL-ORAN-SYNC] Fintables MCP token geçersiz, senkronizasyon atlanıyor");
            return;
        }

        doSync();
    }

    /**
     * Asıl senkronizasyon mantığı.
     *
     * <p>Fintables MCP'den son 5000 finansal oran kaydını SQL ile çeker,
     * tüm sonucu tek bir EnrichmentCache kaydı olarak saklar.</p>
     */
    @Async
    public void doSync() {
        LocalDate today = LocalDate.now(ISTANBUL_ZONE);

        // İdempotent: zaten cache'lenmiş mi?
        boolean exists = cacheRepository.existsByStockCodeAndCacheDateAndDataType(
                SYSTEM_STOCK_CODE, today, EnrichmentDataTypeEnum.FINANSAL_ORAN);
        if (exists) {
            log.info("[FINANSAL-ORAN-SYNC] Bugün ({}) için kayıt zaten mevcut, atlanıyor", today);
            return;
        }

        log.info("[FINANSAL-ORAN-SYNC] Başlıyor: tarih={}", today);

        try {
            String sql = "SELECT * FROM hisse_finansal_tablolari_finansal_oranlari " +
                    "ORDER BY hisse_senedi_kodu, yil DESC, ay DESC LIMIT 5000";

            JsonNode result = mcpClient.veriSorgula(sql, "Finansal oranlar");

            if (result == null) {
                log.warn("[FINANSAL-ORAN-SYNC] MCP null yanıt döndü");
                return;
            }

            // MCP result -> content[0].text içindeki veriyi al
            String responseText = extractResponseText(result);
            if (responseText == null || responseText.isBlank()) {
                log.warn("[FINANSAL-ORAN-SYNC] MCP boş yanıt döndü");
                return;
            }

            // Tüm sonucu tek kayıt olarak sakla
            EnrichmentCache cache = EnrichmentCache.builder()
                    .stockCode(SYSTEM_STOCK_CODE)
                    .cacheDate(today)
                    .dataType(EnrichmentDataTypeEnum.FINANSAL_ORAN)
                    .jsonData(responseText)
                    .build();
            cacheRepository.save(cache);

            log.info("[FINANSAL-ORAN-SYNC] Tamamlandı: tarih={}, veri boyutu={} karakter",
                    today, responseText.length());

            // Eski verileri temizle (save sonrası, try içinde)
            cleanupOldData(today);

        } catch (Exception e) {
            log.error("[FINANSAL-ORAN-SYNC] Hata: tarih={}", today, e);
        }
    }

    /**
     * MCP JSON-RPC result nesnesinden metin yanıtını çıkarır.
     *
     * <p>MCP yanıt formatı: {@code result.content[0].text}</p>
     *
     * @param result JSON-RPC result alanı
     * @return metin yanıtı veya null
     */
    private String extractResponseText(JsonNode result) {
        try {
            JsonNode content = result.get("content");
            if (content != null && content.isArray() && !content.isEmpty()) {
                JsonNode firstContent = content.get(0);
                if (firstContent.has("text")) {
                    return firstContent.get("text").asText();
                }
            }
            // content yoksa doğrudan result'ın kendisini JSON string olarak dön
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("[FINANSAL-ORAN-SYNC] Response text çıkarma hatası", e);
            return null;
        }
    }

    /**
     * 30 günden eski FINANSAL_ORAN cache kayıtlarını temizler.
     *
     * @param today referans tarih
     */
    private void cleanupOldData(LocalDate today) {
        try {
            int deleted = cacheRepository.deleteByDataTypeOlderThan(
                    EnrichmentDataTypeEnum.FINANSAL_ORAN, today.minusDays(CLEANUP_DAYS));
            if (deleted > 0) {
                log.info("[FINANSAL-ORAN-SYNC] {} eski FINANSAL_ORAN kaydı silindi", deleted);
            }
        } catch (Exception e) {
            log.warn("[FINANSAL-ORAN-SYNC] Eski veri temizleme hatası", e);
        }
    }
}
