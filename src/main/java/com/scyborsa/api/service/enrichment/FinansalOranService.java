package com.scyborsa.api.service.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.dto.fintables.FinansalOranDto;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Finansal oranlar servis katmanı.
 *
 * <p>EnrichmentCache'den finansal oran verilerini okur, parse eder ve DTO listesi olarak döndürür.
 * Cache'de veri yoksa ve Fintables MCP token geçerliyse canlı sorgu yapar (fallback).</p>
 *
 * <p>Veri kaynağı: Fintables MCP {@code hisse_finansal_tablolari_finansal_oranlari} tablosu.
 * Cache'de {@code _SYSTEM_} stockCode ile tek kayıt olarak saklanır (piyasa geneli).</p>
 *
 * @see FinansalOranSyncJob
 * @see FinansalOranDto
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinansalOranService {

    /** Istanbul saat dilimi. */
    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");

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
     * Tüm finansal oranları döndürür.
     *
     * <p>Önce EnrichmentCache'den okumaya çalışır. Cache'de yoksa ve MCP token
     * geçerliyse canlı sorgu yapar.</p>
     *
     * @return finansal oran DTO listesi (hisse kodu, yıl DESC, ay DESC sıralı), veri yoksa boş liste
     */
    public List<FinansalOranDto> getFinansalOranlar() {
        LocalDate today = LocalDate.now(ISTANBUL_ZONE);

        // 1. Cache'den oku
        Optional<EnrichmentCache> cached = cacheRepository.findByStockCodeAndCacheDateAndDataType(
                SYSTEM_STOCK_CODE, today, EnrichmentDataTypeEnum.FINANSAL_ORAN);
        if (cached.isPresent()) {
            return parseMarkdownTable(cached.get().getJsonData());
        }

        // 2. Fallback: MCP canlı sorgu
        if (!tokenStore.isTokenValid()) {
            log.warn("[FINANSAL-ORAN] Cache'de veri yok ve MCP token geçersiz");
            return Collections.emptyList();
        }

        try {
            String sql = "SELECT * FROM hisse_finansal_tablolari_finansal_oranlari " +
                    "ORDER BY hisse_senedi_kodu, yil DESC, ay DESC LIMIT 5000";

            JsonNode result = mcpClient.veriSorgula(sql, "Finansal oranlar (canlı)");
            if (result == null) {
                return Collections.emptyList();
            }

            String responseText = extractResponseText(result);
            if (responseText == null || responseText.isBlank()) {
                return Collections.emptyList();
            }

            return parseMarkdownTable(responseText);
        } catch (Exception e) {
            log.error("[FINANSAL-ORAN] MCP canlı sorgu hatası", e);
            return Collections.emptyList();
        }
    }

    /**
     * Belirtilen hisse için finansal oranları döndürür.
     *
     * <p>Önce cache'den filtreler. Cache'de bulunamazsa hisse bazlı MCP sorgusu
     * ile canlı veri çeker (graceful degradation).</p>
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return ilgili hissenin finansal oranları, yoksa boş liste
     */
    public List<FinansalOranDto> getHisseOranlar(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) {
            return Collections.emptyList();
        }

        // 1. Cache'den filtrele
        List<FinansalOranDto> cached = getFinansalOranlar().stream()
                .filter(dto -> stockCode.equalsIgnoreCase(dto.getHisseSenediKodu()))
                .collect(Collectors.toList());

        if (!cached.isEmpty()) {
            return cached;
        }

        // 2. Cache'de yoksa hisse bazlı MCP fallback
        return fetchHisseOranlarFromMcp(stockCode, null, null);
    }

    /**
     * Belirtilen hisse ve dönem için finansal oranları döndürür.
     *
     * <p>Önce cache'den filtreler. Cache'de bulunamazsa hisse bazlı MCP sorgusu
     * ile canlı veri çeker (graceful degradation).</p>
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @param yil       bilanço yılı (ör: 2025)
     * @param ay        bilanço ayı (3, 6, 9, 12)
     * @return ilgili dönemin finansal oranları, yoksa boş liste
     */
    public List<FinansalOranDto> getHisseOranlar(String stockCode, int yil, int ay) {
        if (stockCode == null || stockCode.isBlank()) {
            return Collections.emptyList();
        }

        // 1. Cache'den filtrele
        List<FinansalOranDto> cached = getFinansalOranlar().stream()
                .filter(dto -> stockCode.equalsIgnoreCase(dto.getHisseSenediKodu())
                        && dto.getYil() != null && dto.getYil().equals(yil)
                        && dto.getAy() != null && dto.getAy().equals(ay))
                .collect(Collectors.toList());

        if (!cached.isEmpty()) {
            return cached;
        }

        // 2. Cache'de yoksa hisse bazlı MCP fallback (yıl+ay filtreli)
        return fetchHisseOranlarFromMcp(stockCode, yil, ay);
    }

    /**
     * Cache'de bulunamayan hisse için MCP üzerinden hisse bazlı canlı sorgu yapar.
     *
     * <p>SQL injection koruması: stockCode {@code ^[A-Z0-9]{1,10}$} regex ile doğrulanır.
     * MCP client veya token yoksa/geçersizse boş liste döner (graceful degradation).</p>
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @param yil       bilanço yılı filtresi (null ise filtre uygulanmaz)
     * @param ay        bilanço ayı filtresi (null ise filtre uygulanmaz)
     * @return MCP'den alınan finansal oran listesi, hata durumunda boş liste
     */
    private List<FinansalOranDto> fetchHisseOranlarFromMcp(String stockCode, Integer yil, Integer ay) {
        if (mcpClient == null || tokenStore == null || !tokenStore.isTokenValid()) {
            return Collections.emptyList();
        }

        String safeName = stockCode.trim().toUpperCase();
        if (!safeName.matches("^[A-Z0-9]{1,10}$")) {
            log.warn("[FINANSAL-ORAN] Geçersiz stockCode formatı: {}", stockCode);
            return Collections.emptyList();
        }

        try {
            StringBuilder sql = new StringBuilder();
            sql.append("SELECT * FROM hisse_finansal_tablolari_finansal_oranlari ")
               .append("WHERE hisse_senedi_kodu = '").append(safeName).append("' ");

            if (yil != null && ay != null) {
                sql.append("AND yil = ").append(yil).append(" AND ay = ").append(ay).append(" ");
            }

            sql.append("ORDER BY yil DESC, ay DESC, satir_no LIMIT 50");

            JsonNode result = mcpClient.veriSorgula(sql.toString(), "Hisse oranları: " + safeName);
            if (result == null) {
                return Collections.emptyList();
            }

            String responseText = extractResponseText(result);
            if (responseText == null || responseText.isBlank()) {
                return Collections.emptyList();
            }

            List<FinansalOranDto> parsed = parseMarkdownTable(responseText);
            log.debug("[FINANSAL-ORAN] MCP hisse fallback: {} için {} satır alındı", safeName, parsed.size());
            return parsed;
        } catch (Exception e) {
            log.warn("[FINANSAL-ORAN] MCP hisse fallback hatası: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * MCP response'undaki markdown tabloyu parse ederek DTO listesine dönüştürür.
     *
     * <p>Fintables MCP {@code veri_sorgula} yanıtı markdown tablo formatındadır.
     * İlk satır header, ikinci satır ayırıcı, geri kalan satırlar veridir.
     * JSON yanıt ise {@code row_count} ve {@code table} alanlarını içerebilir.</p>
     *
     * @param rawText MCP yanıt metni (markdown tablo veya JSON)
     * @return parse edilmiş DTO listesi
     */
    private List<FinansalOranDto> parseMarkdownTable(String rawText) {
        List<FinansalOranDto> result = new ArrayList<>();

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
                    JsonNode content = json.get("content");
                    if (!content.isEmpty() && content.get(0).has("text")) {
                        tableText = content.get(0).get("text").asText();
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
                log.debug("[FINANSAL-ORAN] Markdown tablo çok kısa: {} satır", lines.length);
                return result;
            }

            // Header satırından kolon indekslerini bul
            String[] headers = parseMdRow(lines[0]);
            int idxKod = findColumnIndex(headers, "hisse_senedi_kodu");
            int idxYil = findColumnIndex(headers, "yil");
            int idxAy = findColumnIndex(headers, "ay");
            int idxSatirNo = findColumnIndex(headers, "satir_no");
            int idxKategori = findColumnIndex(headers, "kategori");
            int idxOran = findColumnIndex(headers, "oran");
            int idxDeger = findColumnIndex(headers, "deger");

            // Satır 0 = header, satır 1 = ayırıcı (---|---), satır 2+ = veri
            for (int i = 2; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty() || line.replaceAll("[|:\\-\\s]", "").isEmpty()) {
                    continue;
                }

                String[] cols = parseMdRow(line);
                if (cols.length == 0) {
                    continue;
                }

                try {
                    FinansalOranDto dto = FinansalOranDto.builder()
                            .hisseSenediKodu(safeGet(cols, idxKod))
                            .yil(parseInteger(safeGet(cols, idxYil)))
                            .ay(parseInteger(safeGet(cols, idxAy)))
                            .satirNo(parseInteger(safeGet(cols, idxSatirNo)))
                            .kategori(safeGet(cols, idxKategori))
                            .oran(safeGet(cols, idxOran))
                            .deger(parseDouble(safeGet(cols, idxDeger)))
                            .build();
                    result.add(dto);
                } catch (Exception e) {
                    log.debug("[FINANSAL-ORAN] Satır parse hatası (satır {}): {}", i, e.getMessage());
                }
            }

            log.debug("[FINANSAL-ORAN] {} satır parse edildi", result.size());

        } catch (Exception e) {
            log.error("[FINANSAL-ORAN] Markdown tablo parse hatası", e);
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
            return Double.valueOf(value.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * String'i Integer'a parse eder.
     *
     * @param value string değer
     * @return Integer değer veya null
     */
    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String cleaned = value.replace(",", "").trim();
            if (cleaned.contains(".")) {
                cleaned = cleaned.substring(0, cleaned.indexOf('.'));
            }
            return Integer.parseInt(cleaned);
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
            log.warn("[FINANSAL-ORAN] Response text çıkarma hatası", e);
            return null;
        }
    }
}
