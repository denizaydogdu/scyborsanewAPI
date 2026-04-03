package com.scyborsa.api.service.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.dto.fintables.VbtsTedbirDto;
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
import java.util.stream.Collectors;

/**
 * VBTS tedbirli hisse servisi.
 *
 * <p>EnrichmentCache'den VBTS tedbir verilerini okur, parse eder ve filtreler.
 * Cache boş ise ve MCP token geçerli ise canlı sorgu ile fallback yapar.</p>
 *
 * @see VbtsTedbirSyncJob
 * @see VbtsTedbirDto
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VbtsTedbirService {

    /** Istanbul saat dilimi. */
    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");

    /** Zenginleştirilmiş veri cache repository. */
    private final EnrichmentCacheRepository cacheRepository;

    /** Fintables MCP istemcisi. */
    private final FintablesMcpClient fintablesMcpClient;

    /** MCP token saklama bileşeni. */
    private final FintablesMcpTokenStore tokenStore;

    /** JSON serializasyon için ObjectMapper. */
    private final ObjectMapper objectMapper;

    /**
     * Tüm aktif VBTS tedbirlerini getirir.
     *
     * <p>Önce bugünün cache'inden okur. Cache yoksa ve MCP token geçerli ise
     * canlı sorgu yapar.</p>
     *
     * @return aktif tedbir listesi (boş olabilir, asla null değil)
     */
    public List<VbtsTedbirDto> getAktifTedbirler() {
        LocalDate today = LocalDate.now(ISTANBUL_ZONE);

        // Cache'den oku
        var cached = cacheRepository.findByStockCodeAndCacheDateAndDataType(
                VbtsTedbirSyncJob.SYSTEM_STOCK_CODE, today, EnrichmentDataTypeEnum.VBTS_TEDBIR);

        if (cached.isPresent()) {
            return parseMarkdownTable(cached.get().getJsonData());
        }

        // Fallback: MCP'den canlı çek
        return fetchFromMcp(today);
    }

    /**
     * Belirtilen hissenin aktif tedbirlerini getirir.
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return hisseye ait tedbir listesi (boş olabilir, asla null değil)
     */
    public List<VbtsTedbirDto> getHisseTedbirleri(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) {
            return Collections.emptyList();
        }

        String normalizedCode = stockCode.trim().toUpperCase();
        return getAktifTedbirler().stream()
                .filter(t -> normalizedCode.equals(t.getHisseSenediKodu()))
                .collect(Collectors.toList());
    }

    /**
     * Belirtilen hissenin tedbirli olup olmadığını kontrol eder.
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return tedbirli ise {@code true}
     */
    public boolean isTedbirli(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) {
            return false;
        }

        String normalizedCode = stockCode.trim().toUpperCase();
        return getAktifTedbirler().stream()
                .anyMatch(t -> normalizedCode.equals(t.getHisseSenediKodu()));
    }

    /**
     * MCP'den canlı tedbir verisi çeker ve cache'e yazar.
     *
     * @param today bugünün tarihi
     * @return parse edilmiş tedbir listesi
     */
    private List<VbtsTedbirDto> fetchFromMcp(LocalDate today) {
        if (!tokenStore.isTokenValid()) {
            log.debug("[VBTS-SERVICE] MCP token geçersiz, boş liste dönüyor");
            return Collections.emptyList();
        }

        try {
            String todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE);
            String sql = "SELECT * FROM hisse_vbts_tedbirleri " +
                    "WHERE tedbir_bitis_tarihi_europe_istanbul IS NULL " +
                    "OR tedbir_bitis_tarihi_europe_istanbul >= '" + todayStr + "'";

            JsonNode result = fintablesMcpClient.veriSorgula(sql,
                    "Aktif VBTS tedbirli hisseleri getiriyorum (fallback)");

            if (result == null) {
                return Collections.emptyList();
            }

            String responseText = extractResponseText(result);
            if (responseText == null || responseText.isBlank()) {
                return Collections.emptyList();
            }

            // Cache'e yaz (idempotent)
            var existing = cacheRepository.findByStockCodeAndCacheDateAndDataType(
                    VbtsTedbirSyncJob.SYSTEM_STOCK_CODE, today, EnrichmentDataTypeEnum.VBTS_TEDBIR);

            if (existing.isEmpty()) {
                EnrichmentCache cache = EnrichmentCache.builder()
                        .stockCode(VbtsTedbirSyncJob.SYSTEM_STOCK_CODE)
                        .cacheDate(today)
                        .dataType(EnrichmentDataTypeEnum.VBTS_TEDBIR)
                        .jsonData(responseText)
                        .build();
                cacheRepository.save(cache);
                log.info("[VBTS-SERVICE] Fallback cache yazıldı: tarih={}", todayStr);
            }

            return parseMarkdownTable(responseText);

        } catch (Exception e) {
            log.error("[VBTS-SERVICE] MCP fallback hatası", e);
            return Collections.emptyList();
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
            log.warn("[VBTS-SERVICE] Response text çıkarma hatası", e);
            return null;
        }
    }

    /**
     * MCP response'undaki markdown tabloyu parse ederek DTO listesine dönüştürür.
     *
     * <p>Fintables MCP {@code veri_sorgula} yanıtı markdown tablo formatındadır:
     * <pre>
     * | tedbir_id | hisse_senedi_kodu | tedbir_tipi | ... |
     * |---|---|---|---|
     * | 123 | GARAN | brut_takas | ... |
     * </pre>
     * </p>
     *
     * <p>İlk satır header, ikinci satır ayırıcı, geri kalan satırlar veridir.
     * JSON yanıt ise {@code table} alanını içerebilir.</p>
     *
     * @param rawText MCP yanıt metni (markdown tablo veya JSON)
     * @return parse edilmiş tedbir listesi
     */
    private List<VbtsTedbirDto> parseMarkdownTable(String rawText) {
        List<VbtsTedbirDto> result = new ArrayList<>();

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
                if (lines.length < 2) {
                    log.warn("[VBTS-SERVICE] Markdown tablo formatı geçersiz: {} satır", lines.length);
                } else {
                    log.debug("[VBTS-SERVICE] Aktif tedbir yok (boş tablo)");
                }
                return result;
            }

            // Header satırından kolon indekslerini bul
            String[] headers = parseMdRow(lines[0]);
            int idxTedbirId = findColumnIndex(headers, "tedbir_id");
            int idxKod = findColumnIndex(headers, "hisse_senedi_kodu");
            int idxTipi = findColumnIndex(headers, "tedbir_tipi");
            int idxBaslangic = findColumnIndex(headers, "tedbir_baslangic_tarihi_europe_istanbul");
            int idxBitis = findColumnIndex(headers, "tedbir_bitis_tarihi_europe_istanbul");
            int idxKapId = findColumnIndex(headers, "kap_bildirim_id");

            // Satır 0 = header, satır 1 = ayırıcı (---|---), satır 2+ = veri
            for (int i = 2; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty() || line.equals("|") || line.replaceAll("[|:\\-\\s]", "").isEmpty()) {
                    continue;
                }

                String[] cols = parseMdRow(line);
                if (cols.length == 0) {
                    continue;
                }

                try {
                    VbtsTedbirDto dto = VbtsTedbirDto.builder()
                            .tedbirId(parseInteger(safeGet(cols, idxTedbirId)))
                            .hisseSenediKodu(safeGet(cols, idxKod))
                            .tedbirTipi(safeGet(cols, idxTipi))
                            .tedbirBaslangicTarihi(safeGet(cols, idxBaslangic))
                            .tedbirBitisTarihi(safeGet(cols, idxBitis))
                            .kapBildirimId(parseLong(safeGet(cols, idxKapId)))
                            .build();
                    result.add(dto);
                } catch (Exception e) {
                    log.debug("[VBTS-SERVICE] Satır parse hatası (satır {}): {}", i, e.getMessage());
                }
            }

            log.debug("[VBTS-SERVICE] {} satır parse edildi", result.size());

        } catch (Exception e) {
            log.error("[VBTS-SERVICE] Markdown tablo parse hatası", e);
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
     * String'i Integer'a parse eder.
     *
     * @param value string değer
     * @return Integer değer veya null
     */
    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Double.valueOf(value.replace(",", "").trim()).intValue();
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
}
