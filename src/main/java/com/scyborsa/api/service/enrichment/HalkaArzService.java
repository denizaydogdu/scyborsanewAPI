package com.scyborsa.api.service.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.dto.fintables.HalkaArzDto;
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
 * Halka arz verileri servis katmanı.
 *
 * <p>EnrichmentCache'den halka arz verilerini okur, parse eder ve DTO listesi olarak döndürür.
 * Cache'de veri yoksa ve Fintables MCP token geçerliyse canlı sorgu yapar (fallback).</p>
 *
 * <p>Veri kaynağı: Fintables MCP {@code halka_arzlar} tablosu.
 * Cache'de {@code _SYSTEM_} stockCode ile tek kayıt olarak saklanır (piyasa geneli).</p>
 *
 * @see HalkaArzSyncJob
 * @see HalkaArzDto
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HalkaArzService {

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
     * Tüm halka arz verilerini döndürür.
     *
     * <p>Önce EnrichmentCache'den okumaya çalışır. Cache'de yoksa ve MCP token
     * geçerliyse canlı sorgu yapar.</p>
     *
     * @return halka arz DTO listesi (talep toplama başlangıç tarihine göre azalan sırada), veri yoksa boş liste
     */
    public List<HalkaArzDto> getHalkaArzlar() {
        LocalDate today = LocalDate.now(ISTANBUL_ZONE);

        // 1. Cache'den oku
        Optional<EnrichmentCache> cached = cacheRepository.findByStockCodeAndCacheDateAndDataType(
                SYSTEM_STOCK_CODE, today, EnrichmentDataTypeEnum.HALKA_ARZ);
        if (cached.isPresent()) {
            return parseMarkdownTable(cached.get().getJsonData());
        }

        // 2. Fallback: MCP canlı sorgu
        if (!tokenStore.isTokenValid()) {
            log.warn("[HALKA-ARZ] Cache'de veri yok ve MCP token geçersiz");
            return Collections.emptyList();
        }

        try {
            String sql = "SELECT * FROM halka_arzlar " +
                    "ORDER BY talep_toplama_baslangic_tarihi_europe_istanbul DESC LIMIT 100";

            JsonNode result = mcpClient.veriSorgula(sql, "Halka arz verileri (canlı)");
            if (result == null) {
                return Collections.emptyList();
            }

            String responseText = extractResponseText(result);
            if (responseText == null || responseText.isBlank()) {
                return Collections.emptyList();
            }

            return parseMarkdownTable(responseText);
        } catch (Exception e) {
            log.error("[HALKA-ARZ] MCP canlı sorgu hatası", e);
            return Collections.emptyList();
        }
    }

    /**
     * Aktif halka arzları döndürür (durum kodu filtrelemesi).
     *
     * <p>Tüm halka arz verilerini alır ve durum koduna göre filtreler.
     * "tamamlandi" ve "iptal" durumundaki kayıtlar hariç tutulur.</p>
     *
     * @return aktif halka arz DTO listesi, yoksa boş liste
     */
    public List<HalkaArzDto> getAktifHalkaArzlar() {
        return getHalkaArzlar().stream()
                .filter(dto -> dto.getDurumKodu() != null
                        && !"tamamlandi".equalsIgnoreCase(dto.getDurumKodu())
                        && !"iptal".equalsIgnoreCase(dto.getDurumKodu()))
                .collect(Collectors.toList());
    }

    /**
     * Belirtilen hisse için halka arz verilerini döndürür.
     *
     * <p>Tüm halka arz verilerini alır ve hisse koduna göre filtreler.</p>
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return ilgili hissenin halka arz verileri, yoksa boş liste
     */
    public List<HalkaArzDto> getHisseHalkaArz(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) {
            return Collections.emptyList();
        }
        return getHalkaArzlar().stream()
                .filter(dto -> stockCode.equalsIgnoreCase(dto.getHisseSenediKodu()))
                .collect(Collectors.toList());
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
    private List<HalkaArzDto> parseMarkdownTable(String rawText) {
        List<HalkaArzDto> result = new ArrayList<>();

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
                log.debug("[HALKA-ARZ] Markdown tablo çok kısa: {} satır", lines.length);
                return result;
            }

            // Header satırından kolon indekslerini bul
            String[] headers = parseMdRow(lines[0]);
            int idxKod = findColumnIndex(headers, "hisse_senedi_kodu");
            int idxBaslik = findColumnIndex(headers, "baslik");
            int idxTalepBaslangic = findColumnIndex(headers, "talep_toplama_baslangic_tarihi_europe_istanbul");
            int idxTalepBitis = findColumnIndex(headers, "talep_toplama_bitis_tarihi_europe_istanbul");
            int idxIlkIslem = findColumnIndex(headers, "ilk_islem_tarihi_europe_istanbul");
            int idxFiyat = findColumnIndex(headers, "halka_arz_fiyati");
            int idxDuzFiyat = findColumnIndex(headers, "duzeltilmis_halka_arz_fiyati");
            int idxPayAdedi = findColumnIndex(headers, "pay_adedi");
            int idxEkPay = findColumnIndex(headers, "ek_pay_adedi");
            int idxAraciKurum = findColumnIndex(headers, "araci_kurum");
            int idxKatilim = findColumnIndex(headers, "katilim_endeksi_uygun_mu");
            int idxKatilimci = findColumnIndex(headers, "katilimci_sayisi");
            int idxDurum = findColumnIndex(headers, "durum_kodu");
            int idxYillikKar = findColumnIndex(headers, "yilliklandirilmis_kar");
            int idxOdenmisSermaye = findColumnIndex(headers, "halka_arz_sonrasi_odenmis_sermaye");
            int idxIskonto = findColumnIndex(headers, "iskonto_orani");
            int idxNetKar = findColumnIndex(headers, "net_kar");
            int idxFavok = findColumnIndex(headers, "favok");
            int idxNetBorc = findColumnIndex(headers, "net_borc");

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
                    HalkaArzDto dto = HalkaArzDto.builder()
                            .hisseSenediKodu(safeGet(cols, idxKod))
                            .baslik(safeGet(cols, idxBaslik))
                            .talepToplamaBaslangicTarihi(safeGet(cols, idxTalepBaslangic))
                            .talepToplamaBitisTarihi(safeGet(cols, idxTalepBitis))
                            .ilkIslemTarihi(safeGet(cols, idxIlkIslem))
                            .halkaArzFiyati(parseDouble(safeGet(cols, idxFiyat)))
                            .duzeltilmisHalkaArzFiyati(parseDouble(safeGet(cols, idxDuzFiyat)))
                            .payAdedi(parseLong(safeGet(cols, idxPayAdedi)))
                            .ekPayAdedi(parseLong(safeGet(cols, idxEkPay)))
                            .araciKurum(safeGet(cols, idxAraciKurum))
                            .katilimEndeksiUygunMu(parseBoolean(safeGet(cols, idxKatilim)))
                            .katilimciSayisi(parseInteger(safeGet(cols, idxKatilimci)))
                            .durumKodu(safeGet(cols, idxDurum))
                            .yilliklandirilmisKar(parseDouble(safeGet(cols, idxYillikKar)))
                            .halkaArzSonrasiOdenmisSermaye(parseDouble(safeGet(cols, idxOdenmisSermaye)))
                            .iskontoOrani(parseDouble(safeGet(cols, idxIskonto)))
                            .netKar(parseDouble(safeGet(cols, idxNetKar)))
                            .favok(parseDouble(safeGet(cols, idxFavok)))
                            .netBorc(parseDouble(safeGet(cols, idxNetBorc)))
                            .build();
                    result.add(dto);
                } catch (Exception e) {
                    log.debug("[HALKA-ARZ] Satır parse hatası (satır {}): {}", i, e.getMessage());
                }
            }

            log.debug("[HALKA-ARZ] {} satır parse edildi", result.size());

        } catch (Exception e) {
            log.error("[HALKA-ARZ] Markdown tablo parse hatası", e);
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
            if (cleaned.contains(".")) {
                cleaned = cleaned.substring(0, cleaned.indexOf('.'));
            }
            return Integer.parseInt(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * String'i Boolean'a parse eder.
     *
     * @param value string değer ("true", "false", "1", "0", "evet", "hayır")
     * @return Boolean değer veya null
     */
    private Boolean parseBoolean(String value) {
        if (value == null || value.isBlank()) return null;
        String lower = value.trim().toLowerCase();
        if ("true".equals(lower) || "1".equals(lower) || "evet".equals(lower)) return true;
        if ("false".equals(lower) || "0".equals(lower) || "hayır".equals(lower) || "hayir".equals(lower)) return false;
        return null;
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
            log.warn("[HALKA-ARZ] Response text çıkarma hatası", e);
            return null;
        }
    }
}
