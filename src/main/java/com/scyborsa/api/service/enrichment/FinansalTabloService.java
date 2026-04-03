package com.scyborsa.api.service.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.dto.fintables.FinansalTabloDto;
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
 * Finansal tablo (bilanço, gelir tablosu, nakit akım) servis katmanı.
 *
 * <p>EnrichmentCache'den finansal tablo verilerini okur, markdown tablo formatını
 * parse ederek DTO listesi olarak döndürür. Cache'de veri yoksa ve Fintables MCP
 * token geçerliyse canlı sorgu yapar (fallback).</p>
 *
 * <p>Veri kaynağı: Fintables MCP üzerinden 3 tablo:</p>
 * <ul>
 *   <li>{@code hisse_finansal_tablolari_bilanco_kalemleri} — Bilanço kalemleri</li>
 *   <li>{@code hisse_finansal_tablolari_gelir_tablosu_kalemleri} — Gelir tablosu kalemleri</li>
 *   <li>{@code hisse_finansal_tablolari_nakit_akis_tablosu_kalemleri} — Nakit akım kalemleri</li>
 * </ul>
 *
 * <p>Cache'de {@code _SYSTEM_} stockCode ile tek kayıt olarak saklanır.
 * JSON formatı: {@code {"bilanco":"...","gelir":"...","nakit_akim":"..."}}</p>
 *
 * @see FinansalTabloSyncJob
 * @see FinansalTabloDto
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinansalTabloService {

    /** Istanbul saat dilimi. */
    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");

    /** Piyasa geneli veri için sanal hisse kodu. */
    private static final String SYSTEM_STOCK_CODE = "_SYSTEM_";

    /** Bilanço tablo tipi. */
    private static final String TABLO_BILANCO = "BILANCO";

    /** Gelir tablosu tablo tipi. */
    private static final String TABLO_GELIR = "GELIR";

    /** Nakit akım tablo tipi. */
    private static final String TABLO_NAKIT_AKIM = "NAKIT_AKIM";

    /** SQL sorgularında minimum yıl filtresi. */
    private static final int MIN_YEAR = 2024;

    /** SQL sorgularında satır limiti. */
    private static final int QUERY_LIMIT = 5000;

    /** In-memory cache: parse edilmiş finansal tablo listesi. */
    private volatile List<FinansalTabloDto> cachedTablolar = null;

    /** In-memory cache: son güncelleme zamanı (epoch ms). */
    private volatile long cacheTimestamp = 0L;

    /** In-memory cache TTL: 1 saat. */
    private static final long CACHE_TTL_MS = 60 * 60 * 1000L;

    /** Zenginleştirilmiş veri cache repository. */
    private final EnrichmentCacheRepository cacheRepository;

    /** Fintables MCP istemcisi. */
    private final FintablesMcpClient mcpClient;

    /** Fintables MCP token saklama bileşeni. */
    private final FintablesMcpTokenStore tokenStore;

    /** JSON serializasyon/deserializasyon için ObjectMapper. */
    private final ObjectMapper objectMapper;

    /**
     * Tüm finansal tablo verilerini (bilanço + gelir + nakit akım) döndürür.
     *
     * <p>Önce EnrichmentCache'den okumaya çalışır. Cache'de yoksa ve MCP token
     * geçerliyse canlı sorgu yapar.</p>
     *
     * @return finansal tablo DTO listesi, veri yoksa boş liste
     */
    public List<FinansalTabloDto> getFinansalTablolar() {
        // In-memory cache kontrolü
        if (cachedTablolar != null && (System.currentTimeMillis() - cacheTimestamp) < CACHE_TTL_MS) {
            return cachedTablolar;
        }

        LocalDate today = LocalDate.now(ISTANBUL_ZONE);

        // 1. DB Cache'den oku
        Optional<EnrichmentCache> cached = cacheRepository.findByStockCodeAndCacheDateAndDataType(
                SYSTEM_STOCK_CODE, today, EnrichmentDataTypeEnum.FINANSAL_TABLO);
        if (cached.isPresent()) {
            List<FinansalTabloDto> result = parseCombinedJson(cached.get().getJsonData());
            cachedTablolar = result;
            cacheTimestamp = System.currentTimeMillis();
            return result;
        }

        // 2. Fallback: MCP canlı sorgu
        if (!tokenStore.isTokenValid()) {
            log.warn("[FINANSAL-TABLO] Cache'de veri yok ve MCP token geçersiz");
            return Collections.emptyList();
        }

        try {
            List<FinansalTabloDto> result = new ArrayList<>();
            result.addAll(fetchAndParseLive(buildBilancoSql(), TABLO_BILANCO, "Bilanço kalemleri (canlı)"));
            result.addAll(fetchAndParseLive(buildGelirSql(), TABLO_GELIR, "Gelir tablosu kalemleri (canlı)"));
            result.addAll(fetchAndParseLive(buildNakitSql(), TABLO_NAKIT_AKIM, "Nakit akım kalemleri (canlı)"));
            cachedTablolar = result;
            cacheTimestamp = System.currentTimeMillis();
            return result;
        } catch (Exception e) {
            log.error("[FINANSAL-TABLO] MCP canlı sorgu hatası", e);
            return Collections.emptyList();
        }
    }

    /**
     * Belirtilen hissenin bilanço kalemlerini döndürür.
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return bilanço DTO listesi, yoksa boş liste
     */
    public List<FinansalTabloDto> getHisseBilanco(String stockCode) {
        return getFinansalTablolar().stream()
                .filter(dto -> TABLO_BILANCO.equals(dto.getTabloTipi()))
                .filter(dto -> stockCode.equalsIgnoreCase(dto.getHisseSenediKodu()))
                .collect(Collectors.toList());
    }

    /**
     * Belirtilen hissenin gelir tablosu kalemlerini döndürür.
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return gelir tablosu DTO listesi, yoksa boş liste
     */
    public List<FinansalTabloDto> getHisseGelirTablosu(String stockCode) {
        return getFinansalTablolar().stream()
                .filter(dto -> TABLO_GELIR.equals(dto.getTabloTipi()))
                .filter(dto -> stockCode.equalsIgnoreCase(dto.getHisseSenediKodu()))
                .collect(Collectors.toList());
    }

    /**
     * Belirtilen hissenin nakit akım kalemlerini döndürür.
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return nakit akım DTO listesi, yoksa boş liste
     */
    public List<FinansalTabloDto> getHisseNakitAkim(String stockCode) {
        return getFinansalTablolar().stream()
                .filter(dto -> TABLO_NAKIT_AKIM.equals(dto.getTabloTipi()))
                .filter(dto -> stockCode.equalsIgnoreCase(dto.getHisseSenediKodu()))
                .collect(Collectors.toList());
    }

    /**
     * Birleşik JSON formatındaki cache verisini parse eder.
     *
     * <p>JSON formatı: {@code {"bilanco":"...","gelir":"...","nakit_akim":"..."}}</p>
     *
     * @param jsonData birleşik JSON string
     * @return parse edilmiş DTO listesi
     */
    private List<FinansalTabloDto> parseCombinedJson(String jsonData) {
        if (jsonData == null || jsonData.isBlank()) {
            return Collections.emptyList();
        }

        List<FinansalTabloDto> result = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(jsonData);

            if (root.has("bilanco")) {
                result.addAll(parseMarkdownTable(root.get("bilanco").asText(), TABLO_BILANCO));
            }
            if (root.has("gelir")) {
                result.addAll(parseMarkdownTable(root.get("gelir").asText(), TABLO_GELIR));
            }
            if (root.has("nakit_akim")) {
                result.addAll(parseMarkdownTable(root.get("nakit_akim").asText(), TABLO_NAKIT_AKIM));
            }

        } catch (Exception e) {
            log.error("[FINANSAL-TABLO] Birleşik JSON parse hatası", e);
        }

        return result;
    }

    /**
     * MCP'den canlı veri çeker ve parse eder.
     *
     * @param sql        SQL sorgusu
     * @param tabloTipi  tablo tipi (BILANCO, GELIR, NAKIT_AKIM)
     * @param aciklama   MCP sorgu açıklaması
     * @return parse edilmiş DTO listesi
     */
    private List<FinansalTabloDto> fetchAndParseLive(String sql, String tabloTipi, String aciklama) {
        try {
            JsonNode result = mcpClient.veriSorgula(sql, aciklama);
            if (result == null) {
                return Collections.emptyList();
            }
            String responseText = extractResponseText(result);
            if (responseText == null || responseText.isBlank()) {
                return Collections.emptyList();
            }
            return parseMarkdownTable(responseText, tabloTipi);
        } catch (Exception e) {
            log.error("[FINANSAL-TABLO] MCP canlı sorgu hatası: tabloTipi={}", tabloTipi, e);
            return Collections.emptyList();
        }
    }

    /**
     * MCP response'undaki markdown tabloyu parse ederek DTO listesine dönüştürür.
     *
     * <p>Fintables MCP {@code veri_sorgula} yanıtı markdown tablo formatındadır:
     * <pre>
     * | hisse_senedi_kodu | yil | ay | satir_no | kalem | try_donemsel | ... |
     * |---|---|---|---|---|---|---|
     * | GARAN | 2024 | 12 | 1 | Dönen Varlıklar | 1234567 | ... |
     * </pre>
     * </p>
     *
     * @param rawText   MCP yanıt metni (markdown tablo veya JSON)
     * @param tabloTipi tablo tipi (BILANCO, GELIR, NAKIT_AKIM)
     * @return parse edilmiş DTO listesi
     */
    private List<FinansalTabloDto> parseMarkdownTable(String rawText, String tabloTipi) {
        List<FinansalTabloDto> result = new ArrayList<>();

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
                log.warn("[FINANSAL-TABLO] Markdown tablo çok kısa: {} satır, tabloTipi={}", lines.length, tabloTipi);
                return result;
            }

            // Header satırından kolon indekslerini bul
            String[] headers = parseMdRow(lines[0]);
            int idxKod = findColumnIndex(headers, "hisse_senedi_kodu");
            int idxYil = findColumnIndex(headers, "yil");
            int idxAy = findColumnIndex(headers, "ay");
            int idxSatirNo = findColumnIndex(headers, "satir_no");
            int idxKalem = findColumnIndex(headers, "kalem");
            int idxTryDonemsel = findColumnIndex(headers, "try_donemsel");
            int idxUsdDonemsel = findColumnIndex(headers, "usd_donemsel");
            int idxEurDonemsel = findColumnIndex(headers, "eur_donemsel");

            // Gelir tablosu ve nakit akım ek kolonları
            int idxTryCeyreklik = findColumnIndex(headers, "try_ceyreklik");
            int idxUsdCeyreklik = findColumnIndex(headers, "usd_ceyreklik");
            int idxEurCeyreklik = findColumnIndex(headers, "eur_ceyreklik");
            int idxTryTtm = findColumnIndex(headers, "try_ttm");
            int idxUsdTtm = findColumnIndex(headers, "usd_ttm");
            int idxEurTtm = findColumnIndex(headers, "eur_ttm");

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
                    FinansalTabloDto dto = FinansalTabloDto.builder()
                            .hisseSenediKodu(safeGet(cols, idxKod))
                            .yil(parseInteger(safeGet(cols, idxYil)))
                            .ay(parseInteger(safeGet(cols, idxAy)))
                            .satirNo(parseInteger(safeGet(cols, idxSatirNo)))
                            .kalem(safeGet(cols, idxKalem))
                            .tryDonemsel(parseLong(safeGet(cols, idxTryDonemsel)))
                            .usdDonemsel(parseLong(safeGet(cols, idxUsdDonemsel)))
                            .eurDonemsel(parseLong(safeGet(cols, idxEurDonemsel)))
                            .tryCeyreklik(parseLong(safeGet(cols, idxTryCeyreklik)))
                            .usdCeyreklik(parseLong(safeGet(cols, idxUsdCeyreklik)))
                            .eurCeyreklik(parseLong(safeGet(cols, idxEurCeyreklik)))
                            .tryTtm(parseLong(safeGet(cols, idxTryTtm)))
                            .usdTtm(parseLong(safeGet(cols, idxUsdTtm)))
                            .eurTtm(parseLong(safeGet(cols, idxEurTtm)))
                            .tabloTipi(tabloTipi)
                            .build();
                    result.add(dto);
                } catch (Exception e) {
                    log.debug("[FINANSAL-TABLO] Satır parse hatası (satır {}, tabloTipi={}): {}",
                            i, tabloTipi, e.getMessage());
                }
            }

            log.debug("[FINANSAL-TABLO] {} satır parse edildi, tabloTipi={}", result.size(), tabloTipi);

        } catch (Exception e) {
            log.error("[FINANSAL-TABLO] Markdown tablo parse hatası, tabloTipi={}", tabloTipi, e);
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
     * String'i Integer'a parse eder.
     *
     * @param value string değer
     * @return Integer değer veya null
     */
    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String cleaned = value.replace(",", "").trim();
            // Ondalıklı gelebilir (ör: "2024.0"), substring ile int kısmını al
            int dotIdx = cleaned.indexOf('.');
            if (dotIdx > 0) {
                cleaned = cleaned.substring(0, dotIdx);
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
            log.warn("[FINANSAL-TABLO] Response text çıkarma hatası", e);
            return null;
        }
    }

    /**
     * Bilanço SQL sorgusunu oluşturur.
     *
     * @return bilanço SQL sorgusu
     */
    private String buildBilancoSql() {
        return "SELECT hisse_senedi_kodu, yil, ay, satir_no, kalem, " +
                "try_donemsel, usd_donemsel, eur_donemsel " +
                "FROM hisse_finansal_tablolari_bilanco_kalemleri " +
                "WHERE yil >= " + MIN_YEAR + " " +
                "ORDER BY hisse_senedi_kodu, yil DESC, ay DESC, satir_no " +
                "LIMIT " + QUERY_LIMIT;
    }

    /**
     * Gelir tablosu SQL sorgusunu oluşturur.
     *
     * @return gelir tablosu SQL sorgusu
     */
    private String buildGelirSql() {
        return "SELECT hisse_senedi_kodu, yil, ay, satir_no, kalem, " +
                "try_donemsel, usd_donemsel, eur_donemsel, " +
                "try_ceyreklik, usd_ceyreklik, eur_ceyreklik, " +
                "try_ttm, usd_ttm, eur_ttm " +
                "FROM hisse_finansal_tablolari_gelir_tablosu_kalemleri " +
                "WHERE yil >= " + MIN_YEAR + " " +
                "ORDER BY hisse_senedi_kodu, yil DESC, ay DESC, satir_no " +
                "LIMIT " + QUERY_LIMIT;
    }

    /**
     * Nakit akım SQL sorgusunu oluşturur.
     *
     * @return nakit akım SQL sorgusu
     */
    private String buildNakitSql() {
        return "SELECT hisse_senedi_kodu, yil, ay, satir_no, kalem, " +
                "try_donemsel, usd_donemsel, eur_donemsel, " +
                "try_ceyreklik, usd_ceyreklik, eur_ceyreklik, " +
                "try_ttm, usd_ttm, eur_ttm " +
                "FROM hisse_finansal_tablolari_nakit_akis_tablosu_kalemleri " +
                "WHERE yil >= " + MIN_YEAR + " " +
                "ORDER BY hisse_senedi_kodu, yil DESC, ay DESC, satir_no " +
                "LIMIT " + QUERY_LIMIT;
    }
}
