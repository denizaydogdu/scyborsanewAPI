package com.scyborsa.api.service.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.dto.fintables.AcigaSatisDto;
import com.scyborsa.api.enums.EnrichmentDataTypeEnum;
import com.scyborsa.api.model.EnrichmentCache;
import com.scyborsa.api.repository.EnrichmentCacheRepository;
import com.scyborsa.api.service.client.FintablesMcpClient;
import com.scyborsa.api.service.client.FintablesMcpTokenStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Günlük açığa satış istatistikleri servis katmanı.
 *
 * <p>EnrichmentCache'den açığa satış verilerini okur, parse eder ve DTO listesi olarak döndürür.
 * Cache'de veri yoksa ve Fintables MCP token geçerliyse canlı sorgu yapar (fallback).</p>
 *
 * <p>Veri kaynağı: Fintables MCP {@code gunluk_aciga_satis_istatistikleri} tablosu.
 * Cache'de {@code _SYSTEM_} stockCode ile tek kayıt olarak saklanır (piyasa geneli).</p>
 *
 * @see AcigaSatisSyncJob
 * @see AcigaSatisDto
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AcigaSatisService {

    /** Istanbul saat dilimi. */
    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");

    /** Tarih formatı. */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Piyasa geneli veri için sanal hisse kodu. */
    private static final String SYSTEM_STOCK_CODE = "_SYSTEM_";

    /** Zenginleştirilmiş veri cache repository. */
    private final EnrichmentCacheRepository cacheRepository;

    /** Fintables MCP istemcisi. */
    private final FintablesMcpClient mcpClient;

    /** Fintables MCP token saklama bileşeni. */
    private final FintablesMcpTokenStore tokenStore;

    /** JSON serializasyon/deserializasyon için ObjectMapper. */
    private final ObjectMapper objectMapper;

    /**
     * Bugünün tüm günlük açığa satış verilerini döndürür.
     *
     * <p>Önce EnrichmentCache'den okumaya çalışır. Cache'de yoksa ve MCP token
     * geçerliyse canlı sorgu yapar.</p>
     *
     * @return açığa satış DTO listesi (hacim TL'ye göre azalan sırada), veri yoksa boş liste
     */
    public List<AcigaSatisDto> getGunlukAcigaSatislar() {
        LocalDate today = LocalDate.now(ISTANBUL_ZONE);

        // 1. Cache'den oku
        Optional<EnrichmentCache> cached = cacheRepository.findByStockCodeAndCacheDateAndDataType(
                SYSTEM_STOCK_CODE, today, EnrichmentDataTypeEnum.ACIGA_SATIS);
        if (cached.isPresent()) {
            return parseMarkdownTable(cached.get().getJsonData());
        }

        // 2. Fallback: MCP canlı sorgu
        if (!tokenStore.isTokenValid()) {
            log.warn("[ACIGA-SATIS] Cache'de veri yok ve MCP token geçersiz");
            return Collections.emptyList();
        }

        try {
            String todayStr = today.format(DATE_FMT);
            String sql = "SELECT * FROM gunluk_aciga_satis_istatistikleri " +
                    "WHERE tarih_europe_istanbul = '" + todayStr + "' " +
                    "ORDER BY aciga_satis_hacmi_tl DESC";

            JsonNode result = mcpClient.veriSorgula(sql, "Günlük açığa satış istatistikleri (canlı)");
            if (result == null) {
                return Collections.emptyList();
            }

            String responseText = extractResponseText(result);
            if (responseText == null || responseText.isBlank()) {
                return Collections.emptyList();
            }

            return parseMarkdownTable(responseText);
        } catch (Exception e) {
            log.error("[ACIGA-SATIS] MCP canlı sorgu hatası", e);
            return Collections.emptyList();
        }
    }

    /**
     * Belirtilen hisse için günlük açığa satış verilerini döndürür.
     *
     * <p>Tüm günlük verileri alır ve hisse koduna göre filtreler.</p>
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return ilgili hissenin açığa satış verileri, yoksa boş liste
     */
    public List<AcigaSatisDto> getHisseAcigaSatis(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) {
            return Collections.emptyList();
        }
        return getGunlukAcigaSatislar().stream()
                .filter(dto -> stockCode.equalsIgnoreCase(dto.getHisseSenediKodu()))
                .collect(Collectors.toList());
    }

    /**
     * MCP response'undaki markdown tabloyu parse ederek DTO listesine dönüştürür.
     *
     * <p>Fintables MCP {@code veri_sorgula} yanıtı markdown tablo formatındadır:
     * <pre>
     * | hisse_senedi_kodu | tarih | ortalama_aciga_satis_fiyati | ... |
     * |---|---|---|---|
     * | GARAN | 2026-04-02 | 55.50 | ... |
     * </pre>
     * </p>
     *
     * <p>İlk satır header, ikinci satır ayırıcı, geri kalan satırlar veridir.
     * JSON yanıt ise {@code row_count} ve {@code table} alanlarını içerebilir.</p>
     *
     * @param rawText MCP yanıt metni (markdown tablo veya JSON)
     * @return parse edilmiş DTO listesi
     */
    private List<AcigaSatisDto> parseMarkdownTable(String rawText) {
        List<AcigaSatisDto> result = new ArrayList<>();

        if (rawText == null || rawText.isBlank()) {
            return result;
        }

        try {
            // JSON formatında ise table alanını çıkar
            String tableText = rawText;
            if (rawText.trim().startsWith("{")) {
                JsonNode json = objectMapper.readTree(rawText);
                if (json.has("table")) {
                    tableText = json.get("table").asText();
                } else if (json.has("content") && json.get("content").isArray()) {
                    // MCP result.content[0].text formatı
                    JsonNode content = json.get("content");
                    if (!content.isEmpty() && content.get(0).has("text")) {
                        tableText = content.get(0).get("text").asText();
                        // İçteki text de JSON olabilir
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
                log.warn("[ACIGA-SATIS] Markdown tablo çok kısa: {} satır", lines.length);
                return result;
            }

            // Header satırından kolon indekslerini bul
            String[] headers = parseMdRow(lines[0]);
            int idxKod = findColumnIndex(headers, "hisse_senedi_kodu");
            int idxTarih = findColumnIndex(headers, "tarih");
            int idxOrtFiyat = findColumnIndex(headers, "ortalama_aciga_satis_fiyati");
            int idxEnYuksek = findColumnIndex(headers, "en_yuksek_aciga_satis_fiyati");
            int idxEnDusuk = findColumnIndex(headers, "en_dusuk_aciga_satis_fiyati");
            int idxHacimTl = findColumnIndex(headers, "aciga_satis_hacmi_tl");
            int idxToplamTl = findColumnIndex(headers, "toplam_islem_hacmi_tl");
            int idxLot = findColumnIndex(headers, "aciga_satis_lotu");
            int idxToplamLot = findColumnIndex(headers, "toplam_islem_hacmi_lot");

            // Satır 0 = header, satır 1 = ayırıcı (---|---), satır 2+ = veri
            for (int i = 2; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty() || line.startsWith("|---") || line.equals("|")) {
                    continue;
                }

                String[] cols = parseMdRow(line);
                if (cols.length == 0) {
                    continue;
                }

                try {
                    AcigaSatisDto dto = AcigaSatisDto.builder()
                            .hisseSenediKodu(safeGet(cols, idxKod))
                            .tarih(safeGet(cols, idxTarih))
                            .ortalamaAcigaSatisFiyati(parseDouble(safeGet(cols, idxOrtFiyat)))
                            .enYuksekAcigaSatisFiyati(parseDouble(safeGet(cols, idxEnYuksek)))
                            .enDusukAcigaSatisFiyati(parseDouble(safeGet(cols, idxEnDusuk)))
                            .acigaSatisHacmiTl(parseDouble(safeGet(cols, idxHacimTl)))
                            .toplamIslemHacmiTl(parseDouble(safeGet(cols, idxToplamTl)))
                            .acigaSatisLotu(parseLong(safeGet(cols, idxLot)))
                            .toplamIslemHacmiLot(parseLong(safeGet(cols, idxToplamLot)))
                            .build();
                    result.add(dto);
                } catch (Exception e) {
                    log.debug("[ACIGA-SATIS] Satır parse hatası (satır {}): {}", i, e.getMessage());
                }
            }

            log.debug("[ACIGA-SATIS] {} satır parse edildi", result.size());

        } catch (Exception e) {
            log.error("[ACIGA-SATIS] Markdown tablo parse hatası", e);
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
        // Baştan ve sondan | karakterlerini kaldır, split et
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
     * @param name    aranan kolon adı
     * @return kolon indeksi, bulunamazsa -1
     */
    private int findColumnIndex(String[] headers, String name) {
        for (int i = 0; i < headers.length; i++) {
            if (headers[i].equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Güvenli dizi erişimi.
     *
     * @param arr   dizi
     * @param index erişilecek indeks
     * @return değer veya null (indeks geçersizse)
     */
    private String safeGet(String[] arr, int index) {
        if (index < 0 || index >= arr.length) {
            return null;
        }
        String val = arr[index].trim();
        return val.isEmpty() || "null".equalsIgnoreCase(val) || "None".equalsIgnoreCase(val) ? null : val;
    }

    /**
     * String'i Double'a parse eder.
     *
     * @param value string değer
     * @return Double değer veya null
     */
    private Double parseDouble(String value) {
        if (value == null) return null;
        try {
            return Double.parseDouble(value.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * String'i Long'a parse eder.
     *
     * @param value string değer
     * @return Long değer veya null
     */
    private Long parseLong(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Double.valueOf(value.replace(",", "").trim()).longValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * MCP JSON-RPC result nesnesinden metin yanıtını çıkarır.
     *
     * @param result JSON-RPC result alanı
     * @return metin yanıtı veya null
     */
    private String extractResponseText(JsonNode result) {
        if (result == null) return null;
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
            log.warn("[ACIGA-SATIS] Response text çıkarma hatası", e);
            return null;
        }
    }
}
