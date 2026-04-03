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
import java.time.format.DateTimeFormatter;

/**
 * Günlük açığa satış istatistiklerini Fintables MCP'den çekip EnrichmentCache'e yazan scheduled job.
 *
 * <p>Her iş günü saat 20:00'de (Europe/Istanbul) çalışır.
 * Fintables MCP {@code veri_sorgula} tool'u ile {@code gunluk_aciga_satis_istatistikleri}
 * tablosundan günün verilerini çeker ve tek bir {@link EnrichmentCache} kaydı olarak saklar.</p>
 *
 * <p><b>Özellikler:</b></p>
 * <ul>
 *   <li>İdempotent: Aynı gün için kayıt zaten varsa tekrar yazmaz</li>
 *   <li>Guard clause: Sadece prod profilinde, iş günlerinde ve token geçerliyken çalışır</li>
 *   <li>Async: Scheduler thread'i bloklamaz, ayrı thread'de çalışır</li>
 *   <li>Piyasa geneli veri: stockCode = "_SYSTEM_" (per-hisse değil)</li>
 *   <li>Eski veri temizleme: 30 günden eski ACIGA_SATIS kayıtları silinir</li>
 * </ul>
 *
 * @see AcigaSatisService
 * @see FintablesMcpClient#veriSorgula(String, String)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AcigaSatisSyncJob {

    /** Istanbul saat dilimi. */
    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");

    /** Tarih formatı (Fintables SQL sorgusu için). */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

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
     * Günlük açığa satış senkronizasyonunu tetikler.
     *
     * <p>Her iş günü 20:00'de çalışır. Prod profili, iş günü ve token geçerliliği
     * kontrolü yapar. Asıl işi {@link #doSync()} async metoduna delege eder.</p>
     */
    @Scheduled(cron = "0 0 20 * * MON-FRI", zone = "Europe/Istanbul")
    public void syncDailyData() {
        if (!profileUtils.isProdProfile()) {
            log.debug("[ACIGA-SATIS-SYNC] Prod değil, senkronizasyon atlanıyor");
            return;
        }

        if (!BistTradingCalendar.isTradingDay()) {
            log.debug("[ACIGA-SATIS-SYNC] Tatil günü, senkronizasyon atlanıyor");
            return;
        }

        if (!tokenStore.isTokenValid()) {
            log.warn("[ACIGA-SATIS-SYNC] Fintables MCP token geçersiz, senkronizasyon atlanıyor");
            return;
        }

        doSync();
    }

    /**
     * Guard clause'ları ve idempotent kontrolü atlayarak senkronizasyonu zorla tetikler.
     *
     * <p>Manuel tetikleme ve test amaçlıdır. Bugünkü mevcut cache kaydını siler
     * ve yeniden senkronizasyon yapar.</p>
     */
    public void forceSync() {
        log.info("[ACIGA-SATIS-SYNC] Manuel senkronizasyon başlatıldı");
        LocalDate today = LocalDate.now(ISTANBUL_ZONE);
        // Mevcut kaydı sil (idempotent bypass)
        cacheRepository.findByStockCodeAndCacheDateAndDataType(
                SYSTEM_STOCK_CODE, today, EnrichmentDataTypeEnum.ACIGA_SATIS)
                .ifPresent(cacheRepository::delete);
        doSync();
    }

    /**
     * Asıl senkronizasyon mantığı.
     *
     * <p>Fintables MCP'den günün açığa satış verilerini SQL ile çeker,
     * tüm sonucu tek bir EnrichmentCache kaydı olarak saklar.</p>
     */
    @Async
    public void doSync() {
        LocalDate today = LocalDate.now(ISTANBUL_ZONE);
        String todayStr = today.format(DATE_FMT);

        // İdempotent: zaten cache'lenmiş mi?
        boolean exists = cacheRepository.existsByStockCodeAndCacheDateAndDataType(
                SYSTEM_STOCK_CODE, today, EnrichmentDataTypeEnum.ACIGA_SATIS);
        if (exists) {
            log.info("[ACIGA-SATIS-SYNC] Bugün ({}) için kayıt zaten mevcut, atlanıyor", todayStr);
            return;
        }

        log.info("[ACIGA-SATIS-SYNC] Başlıyor: tarih={}", todayStr);

        try {
            String sql = "SELECT * FROM gunluk_aciga_satis_istatistikleri " +
                    "ORDER BY tarih_europe_istanbul DESC, aciga_satis_hacmi_tl DESC LIMIT 50";

            JsonNode result = mcpClient.veriSorgula(sql, "Günlük açığa satış istatistikleri");

            if (result == null) {
                log.warn("[ACIGA-SATIS-SYNC] MCP null yanıt döndü");
                return;
            }

            // MCP result -> content[0].text içindeki veriyi al
            String responseText = extractResponseText(result);
            if (responseText == null || responseText.isBlank()) {
                log.warn("[ACIGA-SATIS-SYNC] MCP boş yanıt döndü");
                return;
            }

            // Tüm sonucu tek kayıt olarak sakla
            EnrichmentCache cache = EnrichmentCache.builder()
                    .stockCode(SYSTEM_STOCK_CODE)
                    .cacheDate(today)
                    .dataType(EnrichmentDataTypeEnum.ACIGA_SATIS)
                    .jsonData(responseText)
                    .build();
            cacheRepository.save(cache);

            log.info("[ACIGA-SATIS-SYNC] Tamamlandı: tarih={}, veri boyutu={} karakter",
                    todayStr, responseText.length());

            // Eski verileri temizle (save sonrası, try içinde)
            cleanupOldData(today);

        } catch (Exception e) {
            log.error("[ACIGA-SATIS-SYNC] Hata: tarih={}", todayStr, e);
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
            log.warn("[ACIGA-SATIS-SYNC] Response text çıkarma hatası", e);
            return null;
        }
    }

    /**
     * 30 günden eski ACIGA_SATIS cache kayıtlarını temizler.
     *
     * @param today referans tarih
     */
    private void cleanupOldData(LocalDate today) {
        try {
            int deleted = cacheRepository.deleteByDataTypeOlderThan(
                    EnrichmentDataTypeEnum.ACIGA_SATIS, today.minusDays(CLEANUP_DAYS));
            if (deleted > 0) {
                log.info("[ACIGA-SATIS-SYNC] {} eski ACIGA_SATIS kaydı silindi", deleted);
            }
        } catch (Exception e) {
            log.warn("[ACIGA-SATIS-SYNC] Eski veri temizleme hatası", e);
        }
    }
}
