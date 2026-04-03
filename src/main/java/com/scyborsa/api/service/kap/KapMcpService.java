package com.scyborsa.api.service.kap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.dto.fintables.KapMcpHaberDto;
import com.scyborsa.api.service.client.FintablesMcpClient;
import com.scyborsa.api.service.client.FintablesMcpTokenStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * KAP haber servisi — Fintables MCP üzerinden KAP bildirim verilerine erişim.
 *
 * <p>MCP {@code dokumanlarda_ara} tool'u ile KAP bildirimlerinde full-text arama yapar.
 * {@code dokuman_chunk_yukle} tool'u ile haber detay içeriğini getirir.
 * Basit keyword bazlı sınıflandırma ve ek erişimi de sağlar.</p>
 *
 * <p>MCP token geçerli değilse tüm metotlar boş sonuç döndürür (graceful degradation).</p>
 *
 * @see FintablesMcpClient
 * @see KapMcpHaberDto
 */
@Slf4j
@Service
public class KapMcpService {

    /** Hisse kodu doğrulama regex'i (SQL injection koruması). */
    private static final Pattern STOCK_CODE_PATTERN = Pattern.compile("^[A-Z0-9]{1,10}$");

    /** Yüksek önem keyword'leri. */
    private static final List<String> YUKSEK_KEYWORDS = List.of(
            "kar dağıtım", "kâr dağıtım", "sermaye artırım", "birleşme",
            "devralma", "iflas", "konkordato", "tasfiye", "bölünme"
    );

    /** Orta önem keyword'leri. */
    private static final List<String> ORTA_KEYWORDS = List.of(
            "sözleşme", "ihale", "yatırım", "kredi", "tahvil",
            "bedelsiz", "bedelli", "temettü"
    );

    /** JSON parser. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Fintables MCP istemcisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private FintablesMcpClient mcpClient;

    /** MCP token saklama bileşeni. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private FintablesMcpTokenStore tokenStore;

    /**
     * Belirtilen hisse kodu için KAP haberlerini MCP üzerinden arar.
     *
     * <p>MCP {@code dokumanlarda_ara} tool'unu kullanarak "kap_bildirim {stockCode}"
     * sorgusu çalıştırır. Sonuçlar {@link KapMcpHaberDto} listesi olarak döner.</p>
     *
     * @param stockCode hisse kodu (ör: "GARAN"), regex doğrulama yapılır
     * @param limit     maksimum sonuç sayısı
     * @return haber DTO listesi (boş olabilir, asla null değil)
     */
    public List<KapMcpHaberDto> searchKapHaberleri(String stockCode, int limit) {
        if (!isAvailable()) {
            log.debug("[KAP-MCP] MCP mevcut değil veya token geçersiz, boş liste dönülüyor");
            return Collections.emptyList();
        }

        // SQL injection guard
        if (stockCode == null || !STOCK_CODE_PATTERN.matcher(stockCode).matches()) {
            log.warn("[KAP-MCP] Geçersiz hisse kodu: {}", stockCode);
            return Collections.emptyList();
        }

        try {
            String filter = "dokuman_tipi = \"kap_haberi\" AND iliskili_semboller = \"" + stockCode + "\"";
            JsonNode result = mcpClient.dokumanlardaAra(
                    "KAP haberleri: " + stockCode, "", filter, limit);

            return parseHaberSonuclari(result, stockCode, limit);
        } catch (Exception e) {
            log.error("[KAP-MCP] KAP haber araması başarısız, stockCode={}: {}",
                    stockCode, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Chunk ID'leri ile haber detay içeriğini getirir.
     *
     * <p>MCP {@code dokuman_chunk_yukle} tool'unu kullanarak belirtilen
     * chunk'ların metin içeriklerini birleştirir.</p>
     *
     * @param chunkIds yüklenecek chunk ID listesi
     * @return birleştirilmiş chunk metni (boş olabilir)
     */
    public String getHaberDetay(List<String> chunkIds) {
        if (!isAvailable()) {
            log.debug("[KAP-MCP] MCP mevcut değil veya token geçersiz");
            return "";
        }

        if (chunkIds == null || chunkIds.isEmpty()) {
            return "";
        }

        try {
            JsonNode result = mcpClient.dokumanChunkYukle(chunkIds);
            return parseChunkIcerikleri(result);
        } catch (Exception e) {
            log.error("[KAP-MCP] Chunk yükleme başarısız, ids={}: {}", chunkIds, e.getMessage());
            return "";
        }
    }

    /**
     * Haber başlığı ve özetine göre önem seviyesi belirler.
     *
     * <p>Basit keyword bazlı sınıflandırma:</p>
     * <ul>
     *   <li>{@code "YUKSEK"} — kar dağıtım, sermaye artırım, birleşme, devralma, iflas, konkordato</li>
     *   <li>{@code "ORTA"} — sözleşme, ihale, yatırım, kredi</li>
     *   <li>{@code "DUSUK"} — diğer</li>
     * </ul>
     *
     * @param baslik haber başlığı
     * @param ozet   haber özeti
     * @return önem seviyesi: "YUKSEK", "ORTA" veya "DUSUK"
     */
    public String siniflandirHaber(String baslik, String ozet) {
        String metin = ((baslik != null ? baslik : "") + " " + (ozet != null ? ozet : ""))
                .toLowerCase(java.util.Locale.forLanguageTag("tr"));

        for (String keyword : YUKSEK_KEYWORDS) {
            if (metin.contains(keyword)) {
                return "YUKSEK";
            }
        }

        for (String keyword : ORTA_KEYWORDS) {
            if (metin.contains(keyword)) {
                return "ORTA";
            }
        }

        return "DUSUK";
    }

    /**
     * KAP bildirimine ait eklerin chunk ID'lerini getirir.
     *
     * <p>MCP {@code dokumanlarda_ara} tool'u ile "kap_haber_eki {kapBildirimId}"
     * sorgusu çalıştırarak ilgili eklerin chunk'larını bulur.</p>
     *
     * @param kapBildirimId KAP bildirim ID'si
     * @return ek chunk ID listesi (boş olabilir, asla null değil)
     */
    public List<String> getHaberEkleri(Long kapBildirimId) {
        if (!isAvailable()) {
            log.debug("[KAP-MCP] MCP mevcut değil veya token geçersiz");
            return Collections.emptyList();
        }

        if (kapBildirimId == null) {
            return Collections.emptyList();
        }

        try {
            String filter = "dokuman_tipi = \"kap_haber_eki\" AND kap_bildirim_id IN [" + kapBildirimId + "]";
            JsonNode result = mcpClient.dokumanlardaAra(
                    "KAP haber eki: " + kapBildirimId, "", filter, 20);
            return parseChunkIds(result);
        } catch (Exception e) {
            log.error("[KAP-MCP] Haber eki araması başarısız, kapBildirimId={}: {}",
                    kapBildirimId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * MCP istemcisi ve token'ın kullanılabilir olup olmadığını kontrol eder.
     *
     * @return MCP kullanılabilir ise {@code true}
     */
    private boolean isAvailable() {
        return mcpClient != null && tokenStore != null && tokenStore.isTokenValid();
    }

    /**
     * MCP arama sonuçlarını {@link KapMcpHaberDto} listesine dönüştürür.
     *
     * <p>MCP response formatı: {@code content[0].text} içinde JSON string bulunur.
     * JSON yapısı: {@code {"toplam":N, "sonuclar":[{document_title, highlight, ...}]}}</p>
     *
     * @param result    MCP arama sonucu JSON
     * @param stockCode hisse kodu (filtreleme için)
     * @param limit     maksimum sonuç sayısı
     * @return parse edilmiş haber listesi
     */
    private List<KapMcpHaberDto> parseHaberSonuclari(JsonNode result, String stockCode, int limit) {
        List<KapMcpHaberDto> haberler = new ArrayList<>();

        String jsonText = extractTextFromResult(result);
        if (jsonText == null || jsonText.isBlank()) return haberler;

        try {
            JsonNode root = objectMapper.readTree(jsonText);
            JsonNode sonuclar = root.get("sonuclar");
            if (sonuclar == null || !sonuclar.isArray()) return haberler;

            for (JsonNode item : sonuclar) {
                if (haberler.size() >= limit) break;

                KapMcpHaberDto dto = KapMcpHaberDto.builder()
                        .baslik(getTextSafe(item, "document_title"))
                        .ozet(getTextSafe(item, "highlight"))
                        .tarih(getTextSafe(item, "yayinlanma_tarihi_utc"))
                        .bildirimTipi(getTextSafe(item, "kap_bildirim_tipi"))
                        .hisseSenediKodu(stockCode)
                        .chunkIds(extractChunkId(item))
                        .build();
                haberler.add(dto);
            }
        } catch (Exception e) {
            log.warn("[KAP-MCP] JSON parse hatası: {}", e.getMessage());
        }

        return haberler;
    }

    /**
     * MCP result'tan text içeriğini çıkarır.
     *
     * <p>MCP response formatları:</p>
     * <ul>
     *   <li>{@code content[0].text} — standart MCP tool response</li>
     *   <li>{@code text} — doğrudan text alanı</li>
     *   <li>Diğer — toString fallback</li>
     * </ul>
     *
     * @param result MCP sonucu
     * @return text içeriği veya {@code null}
     */
    private String extractTextFromResult(JsonNode result) {
        if (result == null) return null;

        // content[0].text formatı (standart MCP response)
        if (result.has("content") && result.get("content").isArray()) {
            JsonNode content = result.get("content");
            if (!content.isEmpty() && content.get(0).has("text")) {
                return content.get(0).get("text").asText();
            }
        }

        // Doğrudan text alanı
        if (result.has("text")) {
            return result.get("text").asText();
        }

        // Fallback: tüm JSON'u string olarak dön
        return result.toString();
    }

    /**
     * Chunk içeriklerini birleştirir.
     *
     * <p>MCP {@code dokuman_chunk_yukle} response'u da {@code content[0].text} formatında
     * JSON döndürür. İçerideki chunk'ları birleştirir.</p>
     *
     * @param result MCP chunk yükleme sonucu
     * @return birleştirilmiş metin
     */
    private String parseChunkIcerikleri(JsonNode result) {
        if (result == null) return "";

        String jsonText = extractTextFromResult(result);
        if (jsonText == null || jsonText.isBlank()) return "";

        try {
            JsonNode root = objectMapper.readTree(jsonText);

            // Root direkt array ise: [{id, content}, ...]
            if (root.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode chunk : root) {
                    String content = chunk.has("content") ? chunk.get("content").asText()
                            : chunk.has("text") ? chunk.get("text").asText() : null;
                    if (content != null && !content.isBlank()) {
                        if (sb.length() > 0) sb.append("\n\n");
                        sb.append(content);
                    }
                }
                if (sb.length() > 0) return sb.toString();
            }

            // Nested key: sonuclar veya chunks
            JsonNode chunks = root.has("sonuclar") ? root.get("sonuclar")
                    : root.has("chunks") ? root.get("chunks") : null;

            if (chunks != null && chunks.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode chunk : chunks) {
                    String content = chunk.has("content") ? chunk.get("content").asText()
                            : chunk.has("text") ? chunk.get("text").asText() : null;
                    if (content != null && !content.isBlank()) {
                        if (sb.length() > 0) sb.append("\n\n");
                        sb.append(content);
                    }
                }
                if (sb.length() > 0) return sb.toString();
            }

            // Tek text alanı
            if (root.has("text")) {
                return root.get("text").asText();
            }
            if (root.has("content")) {
                return root.get("content").asText();
            }
        } catch (Exception e) {
            log.debug("[KAP-MCP] Chunk içerik parse edilemedi, düz metin olarak kullanılıyor");
        }

        return jsonText;
    }

    /**
     * MCP arama sonucundan chunk ID'lerini çıkarır.
     *
     * <p>Response'taki {@code sonuclar} array'inden {@code id} alanlarını toplar.</p>
     *
     * @param result MCP arama sonucu
     * @return chunk ID listesi
     */
    private List<String> parseChunkIds(JsonNode result) {
        List<String> ids = new ArrayList<>();
        if (result == null) return ids;

        String jsonText = extractTextFromResult(result);
        if (jsonText == null || jsonText.isBlank()) return ids;

        try {
            JsonNode root = objectMapper.readTree(jsonText);
            JsonNode sonuclar = root.get("sonuclar");
            if (sonuclar != null && sonuclar.isArray()) {
                for (JsonNode item : sonuclar) {
                    String id = getTextSafe(item, "id");
                    if (id != null && !id.isBlank()) {
                        ids.add(id);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[KAP-MCP] Chunk ID parse hatası: {}", e.getMessage());
        }

        return ids;
    }

    /**
     * Tek bir sonuç item'dan chunk ID çıkarır.
     *
     * @param item sonuç JSON nodu
     * @return chunk ID listesi (tek elemanlı veya boş)
     */
    private List<String> extractChunkId(JsonNode item) {
        List<String> ids = new ArrayList<>();
        if (item == null) return ids;

        String id = getTextSafe(item, "id");
        if (id != null && !id.isBlank()) {
            ids.add(id);
        }
        return ids;
    }

    /**
     * JSON node'dan güvenli text okuma.
     *
     * @param node  JSON nodu
     * @param field alan adı
     * @return alan değeri veya {@code null}
     */
    private String getTextSafe(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText();
    }
}
