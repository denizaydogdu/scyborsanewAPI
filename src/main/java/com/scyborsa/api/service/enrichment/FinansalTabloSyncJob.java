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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Finansal tablo verilerini (bilanço, gelir tablosu, nakit akım) Fintables MCP'den çekip EnrichmentCache'e yazan scheduled job.
 *
 * <p>Her iş günü saat 22:00'de (Europe/Istanbul) çalışır.
 * Fintables MCP {@code veri_sorgula} tool'u ile 3 farklı tablodan
 * (bilanço kalemleri, gelir tablosu kalemleri, nakit akım tablosu kalemleri)
 * verileri çeker ve tek bir {@link EnrichmentCache} kaydı olarak saklar.</p>
 *
 * <p><b>Özellikler:</b></p>
 * <ul>
 *   <li>İdempotent: Aynı gün için kayıt zaten varsa tekrar yazmaz</li>
 *   <li>Guard clause: Sadece prod profilinde, iş günlerinde ve token geçerliyken çalışır</li>
 *   <li>Async: Scheduler thread'i bloklamaz, ayrı thread'de çalışır</li>
 *   <li>Piyasa geneli veri: stockCode = "_SYSTEM_" (per-hisse değil)</li>
 *   <li>3 SQL sorgusu sırayla çalışır, sonuçlar birleşik JSON olarak saklanır</li>
 *   <li>Eski veri temizleme: 30 günden eski FINANSAL_TABLO kayıtları silinir</li>
 * </ul>
 *
 * @see FinansalTabloService
 * @see FintablesMcpClient#veriSorgula(String, String)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FinansalTabloSyncJob {

    /** Istanbul saat dilimi. */
    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");

    /** Tarih formatı. */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Piyasa geneli veri için kullanılan sanal hisse kodu. */
    private static final String SYSTEM_STOCK_CODE = "_SYSTEM_";

    /** Eski cache kayıtlarının temizleneceği gün eşiği. */
    private static final int CLEANUP_DAYS = 30;

    /** SQL sorgularında minimum yıl filtresi. */
    private static final int MIN_YEAR = 2024;

    /** SQL sorgularında satır limiti. */
    private static final int QUERY_LIMIT = 5000;

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
     * Günlük finansal tablo senkronizasyonunu tetikler.
     *
     * <p>Her iş günü 22:00'de çalışır. Prod profili, iş günü ve token geçerliliği
     * kontrolü yapar. Asıl işi {@link #doSync()} async metoduna delege eder.</p>
     */
    @Scheduled(cron = "0 0 22 * * MON-FRI", zone = "Europe/Istanbul")
    public void syncDailyData() {
        if (!profileUtils.isProdProfile()) {
            log.debug("[FINANSAL-TABLO-SYNC] Prod değil, senkronizasyon atlanıyor");
            return;
        }

        if (!BistTradingCalendar.isTradingDay()) {
            log.debug("[FINANSAL-TABLO-SYNC] Tatil günü, senkronizasyon atlanıyor");
            return;
        }

        if (!tokenStore.isTokenValid()) {
            log.warn("[FINANSAL-TABLO-SYNC] Fintables MCP token geçersiz, senkronizasyon atlanıyor");
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
        log.info("[FINANSAL-TABLO-SYNC] Manuel senkronizasyon başlatıldı");
        LocalDate today = LocalDate.now(ISTANBUL_ZONE);
        cacheRepository.findByStockCodeAndCacheDateAndDataType(
                SYSTEM_STOCK_CODE, today, EnrichmentDataTypeEnum.FINANSAL_TABLO)
                .ifPresent(cacheRepository::delete);
        doSync();
    }

    /**
     * Asıl senkronizasyon mantığı.
     *
     * <p>Fintables MCP'den 3 farklı tablodan (bilanço, gelir tablosu, nakit akım)
     * verileri SQL ile çeker, tüm sonuçları birleşik JSON olarak tek bir
     * EnrichmentCache kaydında saklar.</p>
     */
    @Async
    public void doSync() {
        LocalDate today = LocalDate.now(ISTANBUL_ZONE);
        String todayStr = today.format(DATE_FMT);

        // İdempotent: zaten cache'lenmiş mi?
        boolean exists = cacheRepository.existsByStockCodeAndCacheDateAndDataType(
                SYSTEM_STOCK_CODE, today, EnrichmentDataTypeEnum.FINANSAL_TABLO);
        if (exists) {
            log.info("[FINANSAL-TABLO-SYNC] Bugün ({}) için kayıt zaten mevcut, atlanıyor", todayStr);
            return;
        }

        log.info("[FINANSAL-TABLO-SYNC] Başlıyor: tarih={}", todayStr);

        try {
            // 1. Bilanço kalemleri
            String bilancoSql = "SELECT hisse_senedi_kodu, yil, ay, satir_no, kalem, " +
                    "try_donemsel, usd_donemsel, eur_donemsel " +
                    "FROM hisse_finansal_tablolari_bilanco_kalemleri " +
                    "WHERE yil >= " + MIN_YEAR + " " +
                    "ORDER BY hisse_senedi_kodu, yil DESC, ay DESC, satir_no " +
                    "LIMIT " + QUERY_LIMIT;

            log.info("[FINANSAL-TABLO-SYNC] Bilanço sorgusu çalıştırılıyor...");
            JsonNode bilancoResult = mcpClient.veriSorgula(bilancoSql, "Bilanço kalemleri");
            String bilancoText = extractResponseText(bilancoResult);

            // Rate limit: MCP çağrıları arası bekleme
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }

            // 2. Gelir tablosu kalemleri
            String gelirSql = "SELECT hisse_senedi_kodu, yil, ay, satir_no, kalem, " +
                    "try_donemsel, usd_donemsel, eur_donemsel, " +
                    "try_ceyreklik, usd_ceyreklik, eur_ceyreklik, " +
                    "try_ttm, usd_ttm, eur_ttm " +
                    "FROM hisse_finansal_tablolari_gelir_tablosu_kalemleri " +
                    "WHERE yil >= " + MIN_YEAR + " " +
                    "ORDER BY hisse_senedi_kodu, yil DESC, ay DESC, satir_no " +
                    "LIMIT " + QUERY_LIMIT;

            log.info("[FINANSAL-TABLO-SYNC] Gelir tablosu sorgusu çalıştırılıyor...");
            JsonNode gelirResult = mcpClient.veriSorgula(gelirSql, "Gelir tablosu kalemleri");
            String gelirText = extractResponseText(gelirResult);

            // Rate limit: MCP çağrıları arası bekleme
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }

            // 3. Nakit akım tablosu kalemleri
            String nakitSql = "SELECT hisse_senedi_kodu, yil, ay, satir_no, kalem, " +
                    "try_donemsel, usd_donemsel, eur_donemsel, " +
                    "try_ceyreklik, usd_ceyreklik, eur_ceyreklik, " +
                    "try_ttm, usd_ttm, eur_ttm " +
                    "FROM hisse_finansal_tablolari_nakit_akis_tablosu_kalemleri " +
                    "WHERE yil >= " + MIN_YEAR + " " +
                    "ORDER BY hisse_senedi_kodu, yil DESC, ay DESC, satir_no " +
                    "LIMIT " + QUERY_LIMIT;

            log.info("[FINANSAL-TABLO-SYNC] Nakit akım sorgusu çalıştırılıyor...");
            JsonNode nakitResult = mcpClient.veriSorgula(nakitSql, "Nakit akım tablosu kalemleri");
            String nakitText = extractResponseText(nakitResult);

            // Partial failure guard: 3 sonucun da dolu olması gerekir
            if (bilancoText == null || gelirText == null || nakitText == null) {
                log.error("[FINANSAL-TABLO-SYNC] Eksik MCP yanıtı, kayıt atlanıyor " +
                        "(bilanço={}, gelir={}, nakit={})",
                        bilancoText != null, gelirText != null, nakitText != null);
                return;
            }

            // 3 sonucu birleşik JSON olarak sakla
            Map<String, String> combined = new LinkedHashMap<>();
            combined.put("bilanco", bilancoText);
            combined.put("gelir", gelirText);
            combined.put("nakit_akim", nakitText);

            String jsonData = objectMapper.writeValueAsString(combined);

            // Tüm sonucu tek kayıt olarak sakla
            EnrichmentCache cache = EnrichmentCache.builder()
                    .stockCode(SYSTEM_STOCK_CODE)
                    .cacheDate(today)
                    .dataType(EnrichmentDataTypeEnum.FINANSAL_TABLO)
                    .jsonData(jsonData)
                    .build();
            cacheRepository.save(cache);

            log.info("[FINANSAL-TABLO-SYNC] Tamamlandı: tarih={}, veri boyutu={} karakter " +
                            "(bilanço={}, gelir={}, nakit={})",
                    todayStr, jsonData.length(),
                    bilancoText.length(), gelirText.length(), nakitText.length());

            // Eski verileri temizle
            cleanupOldData(today);

        } catch (Exception e) {
            log.error("[FINANSAL-TABLO-SYNC] Hata: tarih={}", todayStr, e);
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
        if (result == null) {
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
            log.warn("[FINANSAL-TABLO-SYNC] Response text çıkarma hatası", e);
            return null;
        }
    }

    /**
     * 30 günden eski FINANSAL_TABLO cache kayıtlarını temizler.
     *
     * @param today referans tarih
     */
    private void cleanupOldData(LocalDate today) {
        try {
            int deleted = cacheRepository.deleteByDataTypeOlderThan(
                    EnrichmentDataTypeEnum.FINANSAL_TABLO, today.minusDays(CLEANUP_DAYS));
            if (deleted > 0) {
                log.info("[FINANSAL-TABLO-SYNC] {} eski FINANSAL_TABLO kaydı silindi", deleted);
            }
        } catch (Exception e) {
            log.warn("[FINANSAL-TABLO-SYNC] Eski veri temizleme hatası", e);
        }
    }
}
