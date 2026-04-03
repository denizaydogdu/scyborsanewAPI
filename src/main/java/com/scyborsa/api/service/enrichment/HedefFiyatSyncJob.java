package com.scyborsa.api.service.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.enums.EnrichmentDataTypeEnum;
import com.scyborsa.api.model.EnrichmentCache;
import com.scyborsa.api.repository.EnrichmentCacheRepository;
import com.scyborsa.api.service.client.FintablesMcpClient;
import com.scyborsa.api.service.client.FintablesMcpTokenStore;
import com.scyborsa.api.utils.ProfileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Haftalık analist hedef fiyat verilerini Fintables MCP'den çekip EnrichmentCache'e yazan scheduled job.
 *
 * <p>Her Pazar saat 21:00'de (Europe/Istanbul) çalışır.
 * Fintables MCP {@code veri_sorgula} tool'u ile {@code hisse_senedi_araci_kurum_hedef_fiyatlari}
 * tablosundan son 500 kaydı çeker ve tek bir {@link EnrichmentCache} kaydı olarak saklar.</p>
 *
 * <p><b>Özellikler:</b></p>
 * <ul>
 *   <li>İdempotent: Aynı gün için kayıt zaten varsa tekrar yazmaz</li>
 *   <li>Guard clause: Sadece prod profilinde ve token geçerliyken çalışır</li>
 *   <li>Async: Scheduler thread'i bloklamaz, ayrı thread'de çalışır</li>
 *   <li>Piyasa geneli veri: stockCode = "_SYSTEM_" (per-hisse değil)</li>
 *   <li>Eski veri temizleme: 30 günden eski HEDEF_FIYAT kayıtları silinir</li>
 * </ul>
 *
 * @see HedefFiyatService
 * @see FintablesMcpClient#veriSorgula(String, String)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HedefFiyatSyncJob {

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
     * Haftalık hedef fiyat senkronizasyonunu tetikler.
     *
     * <p>Her Pazar 21:00'de çalışır. Prod profili, iş günü ve token geçerliliği
     * kontrolü yapar. Asıl işi {@link #doSync()} async metoduna delege eder.</p>
     */
    @Scheduled(cron = "0 0 21 * * SUN", zone = "Europe/Istanbul")
    public void syncWeeklyData() {
        if (!profileUtils.isProdProfile()) {
            log.debug("[HEDEF-FIYAT-SYNC] Prod değil, senkronizasyon atlanıyor");
            return;
        }

        if (!tokenStore.isTokenValid()) {
            log.warn("[HEDEF-FIYAT-SYNC] Fintables MCP token geçersiz, senkronizasyon atlanıyor");
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
        log.info("[HEDEF-FIYAT-SYNC] Manuel senkronizasyon başlatıldı");
        LocalDate today = LocalDate.now(ISTANBUL_ZONE);
        cacheRepository.findByStockCodeAndCacheDateAndDataType(
                SYSTEM_STOCK_CODE, today, EnrichmentDataTypeEnum.HEDEF_FIYAT)
                .ifPresent(cacheRepository::delete);
        doSync();
    }

    /**
     * Asıl senkronizasyon mantığı.
     *
     * <p>Fintables MCP'den son 500 hedef fiyat kaydını SQL ile çeker,
     * tüm sonucu tek bir EnrichmentCache kaydı olarak saklar.</p>
     */
    @Async
    public void doSync() {
        LocalDate today = LocalDate.now(ISTANBUL_ZONE);

        // İdempotent: zaten cache'lenmiş mi?
        boolean exists = cacheRepository.existsByStockCodeAndCacheDateAndDataType(
                SYSTEM_STOCK_CODE, today, EnrichmentDataTypeEnum.HEDEF_FIYAT);
        if (exists) {
            log.info("[HEDEF-FIYAT-SYNC] Bugün ({}) için kayıt zaten mevcut, atlanıyor", today);
            return;
        }

        log.info("[HEDEF-FIYAT-SYNC] Başlıyor: tarih={}", today);

        try {
            String sql = "SELECT * FROM hisse_senedi_araci_kurum_hedef_fiyatlari " +
                    "ORDER BY yayin_tarihi_europe_istanbul DESC LIMIT 500";

            JsonNode result = mcpClient.veriSorgula(sql, "Analist hedef fiyatları");

            if (result == null) {
                log.warn("[HEDEF-FIYAT-SYNC] MCP null yanıt döndü");
                return;
            }

            // MCP result -> content[0].text içindeki veriyi al
            String responseText = extractResponseText(result);
            if (responseText == null || responseText.isBlank()) {
                log.warn("[HEDEF-FIYAT-SYNC] MCP boş yanıt döndü");
                return;
            }

            // Tüm sonucu tek kayıt olarak sakla
            EnrichmentCache cache = EnrichmentCache.builder()
                    .stockCode(SYSTEM_STOCK_CODE)
                    .cacheDate(today)
                    .dataType(EnrichmentDataTypeEnum.HEDEF_FIYAT)
                    .jsonData(responseText)
                    .build();
            cacheRepository.save(cache);

            log.info("[HEDEF-FIYAT-SYNC] Tamamlandı: tarih={}, veri boyutu={} karakter",
                    today, responseText.length());

            // Eski verileri temizle (save sonrası, try içinde)
            cleanupOldData(today);

        } catch (Exception e) {
            log.error("[HEDEF-FIYAT-SYNC] Hata: tarih={}", today, e);
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
            log.warn("[HEDEF-FIYAT-SYNC] Response text çıkarma hatası", e);
            return null;
        }
    }

    /**
     * 30 günden eski HEDEF_FIYAT cache kayıtlarını temizler.
     *
     * @param today referans tarih
     */
    private void cleanupOldData(LocalDate today) {
        try {
            int deleted = cacheRepository.deleteByDataTypeOlderThan(
                    EnrichmentDataTypeEnum.HEDEF_FIYAT, today.minusDays(CLEANUP_DAYS));
            if (deleted > 0) {
                log.info("[HEDEF-FIYAT-SYNC] {} eski HEDEF_FIYAT kaydı silindi", deleted);
            }
        } catch (Exception e) {
            log.warn("[HEDEF-FIYAT-SYNC] Eski veri temizleme hatası", e);
        }
    }
}
