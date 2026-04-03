package com.scyborsa.api.service.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * VBTS tedbirli hisseleri Fintables MCP'den çekip EnrichmentCache'e yazan scheduled job.
 *
 * <p>Her iş günü saat 09:30'da (Europe/Istanbul) çalışır. Fintables MCP üzerinden
 * {@code hisse_vbts_tedbirleri} tablosundan aktif tedbirleri (bitiş tarihi null veya
 * gelecekte olan) çeker ve tek bir {@code _SYSTEM_} kaydı olarak EnrichmentCache'e yazar.</p>
 *
 * <p><b>Özellikler:</b></p>
 * <ul>
 *   <li>Guard: Sadece prod profilinde, iş günlerinde ve geçerli MCP token varsa çalışır</li>
 *   <li>İdempotent: Aynı gün için tekrar çalıştığında mevcut kaydı günceller</li>
 *   <li>Tek kayıt: Tüm tedbirler tek EnrichmentCache satırında JSON olarak saklanır</li>
 *   <li>Temizlik: 30 günden eski VBTS_TEDBIR kayıtları silinir</li>
 *   <li>Async: Scheduler thread'i bloklamaz</li>
 * </ul>
 *
 * @see VbtsTedbirService
 * @see FintablesMcpClient#veriSorgula(String, String)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VbtsTedbirSyncJob {

    /** Istanbul saat dilimi. */
    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");

    /** Eski cache kayıtlarının temizleneceği gün eşiği. */
    private static final int CLEANUP_DAYS = 30;

    /** Sistem geneli veri için kullanılan stockCode değeri. */
    static final String SYSTEM_STOCK_CODE = "_SYSTEM_";

    /** Fintables MCP istemcisi. */
    private final FintablesMcpClient fintablesMcpClient;

    /** MCP token saklama bileşeni. */
    private final FintablesMcpTokenStore tokenStore;

    /** Zenginleştirilmiş veri cache repository. */
    private final EnrichmentCacheRepository cacheRepository;

    /** Spring profil kontrol utility. */
    private final ProfileUtils profileUtils;

    /**
     * Günlük VBTS tedbir senkronizasyonunu tetikler.
     *
     * <p>Her iş günü 09:30'da çalışır. Prod profili, iş günü ve MCP token
     * kontrolleri yapar. Asıl işi {@link #doSync()} async metoduna delege eder.</p>
     */
    @Scheduled(cron = "0 30 9 * * MON-FRI", zone = "Europe/Istanbul")
    public void syncVbtsTedbirData() {
        if (!profileUtils.isProdProfile()) {
            log.debug("[VBTS-SYNC] Prod değil, senkronizasyon atlanıyor");
            return;
        }

        if (!BistTradingCalendar.isTradingDay()) {
            log.debug("[VBTS-SYNC] Tatil günü, senkronizasyon atlanıyor");
            return;
        }

        if (!tokenStore.isTokenValid()) {
            log.warn("[VBTS-SYNC] Fintables MCP token geçersiz, senkronizasyon atlanıyor");
            return;
        }

        doSync();
    }

    /**
     * Guard clause'ları atlayarak senkronizasyonu zorla tetikler.
     *
     * <p>Manuel tetikleme ve test amaçlıdır. Bugünkü mevcut cache kaydını siler
     * ve yeniden senkronizasyon yapar.</p>
     */
    public void forceSync() {
        log.info("[VBTS-SYNC] Manuel senkronizasyon başlatıldı");
        LocalDate today = LocalDate.now(ISTANBUL_ZONE);
        cacheRepository.findByStockCodeAndCacheDateAndDataType(
                SYSTEM_STOCK_CODE, today, EnrichmentDataTypeEnum.VBTS_TEDBIR)
                .ifPresent(cacheRepository::delete);
        doSync();
    }

    /**
     * Asıl senkronizasyon işlemi.
     *
     * <p>Fintables MCP üzerinden aktif VBTS tedbirlerini çeker ve
     * {@code _SYSTEM_} stockCode ile EnrichmentCache'e yazar.</p>
     */
    @Async
    public void doSync() {
        LocalDate today = LocalDate.now(ISTANBUL_ZONE);
        String todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE);

        log.info("[VBTS-SYNC] Başlıyor: tarih={}", todayStr);

        try {
            // Aktif tedbirleri çek (bitmemiş veya bitiş tarihi geçmemiş)
            String sql = "SELECT * FROM hisse_vbts_tedbirleri " +
                    "WHERE tedbir_bitis_tarihi_europe_istanbul IS NULL " +
                    "OR tedbir_bitis_tarihi_europe_istanbul >= '" + todayStr + "'";

            JsonNode result = fintablesMcpClient.veriSorgula(sql,
                    "Aktif VBTS tedbirli hisseleri getiriyorum");

            if (result == null) {
                log.warn("[VBTS-SYNC] MCP null yanıt döndü");
                return;
            }

            String jsonData = extractResponseText(result);
            if (jsonData == null || jsonData.isBlank()) {
                log.warn("[VBTS-SYNC] MCP boş yanıt döndü");
                return;
            }
            log.info("[VBTS-SYNC] MCP'den veri alındı: {} karakter", jsonData.length());

            // Mevcut kaydı kontrol et (idempotent)
            var existing = cacheRepository.findByStockCodeAndCacheDateAndDataType(
                    SYSTEM_STOCK_CODE, today, EnrichmentDataTypeEnum.VBTS_TEDBIR);

            if (existing.isPresent()) {
                // Güncelle
                EnrichmentCache cache = existing.get();
                cache.setJsonData(jsonData);
                cacheRepository.save(cache);
                log.info("[VBTS-SYNC] Mevcut kayıt güncellendi: tarih={}", todayStr);
            } else {
                // Yeni kayıt
                EnrichmentCache cache = EnrichmentCache.builder()
                        .stockCode(SYSTEM_STOCK_CODE)
                        .cacheDate(today)
                        .dataType(EnrichmentDataTypeEnum.VBTS_TEDBIR)
                        .jsonData(jsonData)
                        .build();
                cacheRepository.save(cache);
                log.info("[VBTS-SYNC] Yeni kayıt oluşturuldu: tarih={}", todayStr);
            }

            // Eski verileri temizle
            cleanupOldData(today);

        } catch (Exception e) {
            log.error("[VBTS-SYNC] Senkronizasyon hatası", e);
        }
    }

    /**
     * MCP JSON-RPC result nesnesinden metin yanıtını çıkarır.
     *
     * <p>MCP yanıt formatı: {@code result.content[0].text}</p>
     *
     * @param result JSON-RPC result alanı
     * @return metin yanıtı veya result'ın string hali
     */
    private String extractResponseText(JsonNode result) {
        if (result.has("content") && result.get("content").isArray()) {
            JsonNode content = result.get("content");
            if (!content.isEmpty() && content.get(0).has("text")) {
                return content.get(0).get("text").asText();
            }
        }
        return result.toString();
    }

    /**
     * 30 günden eski VBTS_TEDBIR cache kayıtlarını temizler.
     *
     * @param today referans tarih
     */
    private void cleanupOldData(LocalDate today) {
        try {
            int deleted = cacheRepository.deleteByDataTypeOlderThan(
                    EnrichmentDataTypeEnum.VBTS_TEDBIR,
                    today.minusDays(CLEANUP_DAYS));
            if (deleted > 0) {
                log.info("[VBTS-SYNC] {} eski VBTS_TEDBIR kaydı silindi", deleted);
            }
        } catch (Exception e) {
            log.warn("[VBTS-SYNC] Eski veri temizleme hatası", e);
        }
    }
}
