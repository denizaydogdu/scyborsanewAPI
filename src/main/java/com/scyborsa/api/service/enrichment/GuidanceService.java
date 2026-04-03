package com.scyborsa.api.service.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.dto.fintables.GuidanceDto;
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
import java.util.regex.Pattern;

/**
 * Sirket guidance (beklenti) servis katmani.
 *
 * <p>EnrichmentCache'den guidance hisse+yil listesini okur ve DTO listesi olarak dondurur.
 * Hisse bazli detay (beklentiler metni) icin dogrudan MCP sorgusu yapar.</p>
 *
 * <p>Veri kaynagi: Fintables MCP {@code guidance} tablosu.
 * Cache'de {@code _SYSTEM_} stockCode ile tek kayit olarak saklanir (sadece hisse+yil listesi).
 * Beklentiler metni cache'lenmez, her seferinde canli MCP sorgusu ile alinir.</p>
 *
 * @see GuidanceSyncJob
 * @see GuidanceDto
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuidanceService {

    /** Istanbul saat dilimi. */
    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");

    /** Piyasa geneli veri icin sanal hisse kodu. */
    private static final String SYSTEM_STOCK_CODE = "_SYSTEM_";

    /** Gecerli BIST hisse kodu pattern'i: buyuk harf ve rakam, 1-10 karakter. */
    private static final Pattern VALID_STOCK_CODE = Pattern.compile("^[A-Z0-9]{1,10}$");

    /** Zenginlestirilmis veri cache repository. */
    private final EnrichmentCacheRepository cacheRepository;

    /** Fintables MCP istemcisi. */
    private final FintablesMcpClient mcpClient;

    /** Fintables MCP token saklama bileseni. */
    private final FintablesMcpTokenStore tokenStore;

    /** JSON serializasyon/deserializasyon icin ObjectMapper. */
    private final ObjectMapper objectMapper;

    /**
     * Tum guidance verilerini dondurur (sadece hisse kodu + yil, beklentiler YOK).
     *
     * <p>Once EnrichmentCache'den okumaya calisir. Cache'de yoksa ve MCP token
     * gecerliyse canli sorgu yapar.</p>
     *
     * @return guidance DTO listesi (beklentiler=null), veri yoksa bos liste
     */
    public List<GuidanceDto> getGuidancelar() {
        LocalDate today = LocalDate.now(ISTANBUL_ZONE);

        // 1. Cache'den oku
        Optional<EnrichmentCache> cached = cacheRepository.findByStockCodeAndCacheDateAndDataType(
                SYSTEM_STOCK_CODE, today, EnrichmentDataTypeEnum.GUIDANCE);
        if (cached.isPresent()) {
            return parseListTable(cached.get().getJsonData());
        }

        // 2. Fallback: MCP canli sorgu
        if (!tokenStore.isTokenValid()) {
            log.warn("[GUIDANCE] Cache'de veri yok ve MCP token gecersiz");
            return Collections.emptyList();
        }

        try {
            String sql = "SELECT DISTINCT hisse_senedi_kodu, yil FROM guidance ORDER BY hisse_senedi_kodu, yil DESC";
            JsonNode result = mcpClient.veriSorgula(sql, "Sirket guidance listesi (canli)");
            if (result == null) {
                return Collections.emptyList();
            }

            String responseText = extractResponseText(result);
            if (responseText == null || responseText.isBlank()) {
                return Collections.emptyList();
            }

            return parseListTable(responseText);
        } catch (Exception e) {
            log.error("[GUIDANCE] MCP canli sorgu hatasi", e);
            return Collections.emptyList();
        }
    }

    /**
     * Belirtilen hisse icin guidance verilerini dondurur (beklentiler dahil).
     *
     * <p>Dogrudan MCP sorgusu yapar, beklentiler alani parse edilerek DTO listesi dondurulur.
     * VelzonAiService ve SirketRaporService tarafindan kullanilir.</p>
     *
     * @param stockCode hisse kodu (or: "GARAN")
     * @return ilgili hissenin guidance verileri, yoksa bos liste
     */
    public List<GuidanceDto> getHisseGuidance(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) {
            return Collections.emptyList();
        }

        String safeName = stockCode.trim().toUpperCase();
        if (!VALID_STOCK_CODE.matcher(safeName).matches()) {
            return Collections.emptyList();
        }

        if (!tokenStore.isTokenValid()) {
            log.warn("[GUIDANCE] MCP token gecersiz, hisse guidance alinamadi: {}", safeName);
            return Collections.emptyList();
        }

        try {
            String sql = "SELECT hisse_senedi_kodu, yil, beklentiler FROM guidance WHERE hisse_senedi_kodu = '"
                    + safeName + "' ORDER BY yil DESC";
            JsonNode result = mcpClient.veriSorgula(sql, "Guidance hisse: " + safeName);
            if (result == null) return Collections.emptyList();

            String responseText = extractResponseText(result);
            if (responseText == null || responseText.isBlank()) return Collections.emptyList();

            return parseFullTable(responseText);
        } catch (Exception e) {
            log.error("[GUIDANCE] Hisse guidance MCP sorgu hatasi: {}", safeName, e);
            return Collections.emptyList();
        }
    }

    /**
     * Belirtilen yil icin guidance verilerini dondurur (beklentiler dahil).
     *
     * <p>Dogrudan MCP sorgusu yapar. HalkaArzGuidanceTelegramJob tarafindan kullanilir.</p>
     *
     * @param yil guidance yili (or: 2026)
     * @return ilgili yilin guidance verileri, yoksa bos liste
     */
    public List<GuidanceDto> getYilGuidance(int yil) {
        if (!tokenStore.isTokenValid()) {
            log.warn("[GUIDANCE] MCP token gecersiz, yil guidance alinamadi: {}", yil);
            return Collections.emptyList();
        }

        try {
            String sql = "SELECT hisse_senedi_kodu, yil, beklentiler FROM guidance WHERE yil = "
                    + yil + " ORDER BY hisse_senedi_kodu ASC";
            JsonNode result = mcpClient.veriSorgula(sql, "Guidance yil: " + yil);
            if (result == null) return Collections.emptyList();

            String responseText = extractResponseText(result);
            if (responseText == null || responseText.isBlank()) return Collections.emptyList();

            return parseFullTable(responseText);
        } catch (Exception e) {
            log.error("[GUIDANCE] Yil guidance MCP sorgu hatasi: {}", yil, e);
            return Collections.emptyList();
        }
    }

    /**
     * Belirtilen hisse icin guidance raw metnini MCP'den canli sorgular.
     *
     * <p>Cache KULLANMAZ — her seferinde dogrudan MCP sorgusu yapar.
     * Donen metin beklentiler alaninin ham (raw) markdown metnidir.
     * Parse ETMEZ; UI tarafinda JS ile render edilir.</p>
     *
     * @param stockCode hisse kodu (or: "THYAO")
     * @return MCP response'undaki raw tablo metni, hata durumunda null
     */
    public String getRawGuidance(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) {
            return null;
        }

        // SQL injection guard
        String safeName = stockCode.trim().toUpperCase();
        if (!VALID_STOCK_CODE.matcher(safeName).matches()) {
            log.warn("[GUIDANCE] Gecersiz hisse kodu: {}", stockCode);
            return null;
        }

        if (!tokenStore.isTokenValid()) {
            log.warn("[GUIDANCE] MCP token gecersiz, raw guidance alinamadi: {}", safeName);
            return null;
        }

        try {
            String sql = "SELECT hisse_senedi_kodu, yil, beklentiler FROM guidance WHERE hisse_senedi_kodu = '"
                    + safeName + "' ORDER BY yil DESC";

            JsonNode result = mcpClient.veriSorgula(sql, "Guidance detay: " + safeName);
            if (result == null) {
                log.debug("[GUIDANCE] MCP null yanit: {}", safeName);
                return null;
            }

            String responseText = extractResponseText(result);
            if (responseText == null || responseText.isBlank()) {
                log.debug("[GUIDANCE] MCP bos yanit: {}", safeName);
                return null;
            }

            // Response text'ten table alanini cikar (JSON ise)
            String tableText = extractTableField(responseText);
            return tableText;

        } catch (Exception e) {
            log.error("[GUIDANCE] Raw guidance MCP sorgu hatasi: {}", safeName, e);
            return null;
        }
    }

    /**
     * Beklentiler dahil markdown tabloyu parse eder.
     *
     * <p>Ilk 2 kolonu (hisse_senedi_kodu, yil) ayri parse eder,
     * 3. kolondan itibaren tum metni beklentiler olarak alir.
     * Ic ice pipe iceren beklentiler alanini koruyan remainder parse kullanir.</p>
     *
     * @param rawText MCP yanit metni (markdown tablo veya JSON)
     * @return parse edilmis DTO listesi (beklentiler dahil)
     */
    private List<GuidanceDto> parseFullTable(String rawText) {
        List<GuidanceDto> result = new ArrayList<>();
        if (rawText == null || rawText.isBlank()) return result;

        try {
            String tableText = extractTableField(rawText);
            String[] lines = tableText.split("\n");
            if (lines.length < 3) return result;

            for (int i = 2; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty() || line.equals("|") || line.replaceAll("[|:\\-\\s]", "").isEmpty()) {
                    continue;
                }

                String[] cols = parseMdRowWithRemainder(line, 2);
                if (cols.length == 0) continue;

                try {
                    String hisse = safeGet(cols, 0);
                    Integer yil = parseInteger(safeGet(cols, 1));
                    String beklentiler = (cols.length > 2) ? cols[2] : null;
                    if (beklentiler != null && beklentiler.isBlank()) beklentiler = null;
                    if ("null".equalsIgnoreCase(beklentiler) || "None".equalsIgnoreCase(beklentiler)) {
                        beklentiler = null;
                    }

                    if (hisse == null || !VALID_STOCK_CODE.matcher(hisse).matches()) continue;

                    result.add(GuidanceDto.builder()
                            .hisseSenediKodu(hisse)
                            .yil(yil)
                            .beklentiler(beklentiler)
                            .build());
                } catch (Exception e) {
                    log.debug("[GUIDANCE] Full tablo satir parse hatasi (satir {}): {}", i, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[GUIDANCE] Full tablo parse hatasi", e);
        }

        return result;
    }

    /**
     * Markdown tablo satirini parse eder; ilk {@code fixedCols} kolonu ayri ayri,
     * geri kalanini tek bir kolon olarak birlestirir.
     *
     * @param row       markdown tablo satiri
     * @param fixedCols sabit kolon sayisi (or: 2 = hisse_senedi_kodu + yil)
     * @return fixedCols+1 elemanli dizi
     */
    private String[] parseMdRowWithRemainder(String row, int fixedCols) {
        if (row == null || !row.contains("|")) return new String[0];

        String trimmed = row.trim();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);

        String[] result = new String[fixedCols + 1];
        String remaining = trimmed;

        for (int i = 0; i < fixedCols; i++) {
            int pipeIdx = remaining.indexOf('|');
            if (pipeIdx == -1) {
                result[i] = remaining.trim();
                for (int j = i + 1; j <= fixedCols; j++) result[j] = "";
                return result;
            }
            result[i] = remaining.substring(0, pipeIdx).trim();
            remaining = remaining.substring(pipeIdx + 1);
        }

        if (remaining.endsWith("|")) remaining = remaining.substring(0, remaining.length() - 1);
        result[fixedCols] = remaining.trim();

        return result;
    }

    /**
     * Hisse+yil listesi markdown tablosunu parse eder (beklentiler YOK).
     *
     * @param rawText MCP yanit metni (markdown tablo veya JSON)
     * @return parse edilmis DTO listesi (beklentiler=null)
     */
    private List<GuidanceDto> parseListTable(String rawText) {
        List<GuidanceDto> result = new ArrayList<>();

        if (rawText == null || rawText.isBlank()) {
            return result;
        }

        try {
            // JSON formatinda ise table alanini cikar
            String tableText = extractTableField(rawText);

            String[] lines = tableText.split("\n");
            if (lines.length < 3) {
                log.debug("[GUIDANCE] Markdown tablo cok kisa: {} satir", lines.length);
                return result;
            }

            // Satir 0 = header, satir 1 = ayirici, satir 2+ = veri
            for (int i = 2; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty() || line.equals("|") || line.replaceAll("[|:\\-\\s]", "").isEmpty()) {
                    continue;
                }

                String[] cols = parseMdRow(line);
                if (cols.length < 2) {
                    continue;
                }

                try {
                    String hisse = safeGet(cols, 0);
                    Integer yil = parseInteger(safeGet(cols, 1));

                    if (hisse == null || hisse.isBlank() || !VALID_STOCK_CODE.matcher(hisse).matches()) {
                        continue;
                    }

                    GuidanceDto dto = GuidanceDto.builder()
                            .hisseSenediKodu(hisse)
                            .yil(yil)
                            .beklentiler(null) // Liste modunda beklentiler yok
                            .build();

                    result.add(dto);
                } catch (Exception e) {
                    log.debug("[GUIDANCE] Satir parse hatasi (satir {}): {}", i, e.getMessage());
                }
            }

            log.debug("[GUIDANCE] {} satir parse edildi", result.size());

        } catch (Exception e) {
            log.error("[GUIDANCE] Markdown tablo parse hatasi", e);
        }

        return result;
    }

    /**
     * Raw text'ten JSON {@code table} alanini cikarir. JSON degilse metni oldugu gibi dondurur.
     *
     * @param rawText ham metin (JSON veya markdown)
     * @return table alani veya orjinal metin
     */
    private String extractTableField(String rawText) {
        if (rawText == null) return "";

        try {
            String trimmed = rawText.trim();
            if (trimmed.startsWith("{")) {
                JsonNode json = objectMapper.readTree(trimmed);
                if (json.has("table")) {
                    return json.get("table").asText();
                }
                // Nested content[0].text icinde JSON olabilir
                if (json.has("content") && json.get("content").isArray()) {
                    JsonNode content = json.get("content");
                    if (!content.isEmpty() && content.get(0).has("text")) {
                        String innerText = content.get(0).get("text").asText();
                        if (innerText.trim().startsWith("{")) {
                            JsonNode innerJson = objectMapper.readTree(innerText);
                            if (innerJson.has("table")) {
                                return innerJson.get("table").asText();
                            }
                        }
                        return innerText;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[GUIDANCE] Table alani cikarma hatasi, raw text kullanilacak", e);
        }

        return rawText;
    }

    /**
     * Markdown tablo satirini kolon dizisine donusturur.
     *
     * @param row markdown tablo satiri (or: "| A | B |")
     * @return kolon degerleri (trim'li)
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
     * Guvenli dizi erisimi.
     *
     * @param arr   dizi
     * @param index erisilecek indeks
     * @return deger veya null (indeks gecersizse)
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
     * @param value string deger
     * @return Integer deger veya null
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
     * MCP JSON-RPC result nesnesinden metin yanitini cikarir.
     *
     * @param result JSON-RPC result alani
     * @return metin yaniti veya null
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
            log.warn("[GUIDANCE] Response text cikarma hatasi", e);
            return null;
        }
    }
}
