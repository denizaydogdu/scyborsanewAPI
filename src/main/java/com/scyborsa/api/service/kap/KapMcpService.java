package com.scyborsa.api.service.kap;

import com.fasterxml.jackson.databind.JsonNode;
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
     * @param result    MCP arama sonucu JSON
     * @param stockCode hisse kodu (filtreleme için)
     * @param limit     maksimum sonuç sayısı
     * @return parse edilmiş haber listesi
     */
    private List<KapMcpHaberDto> parseHaberSonuclari(JsonNode result, String stockCode, int limit) {
        List<KapMcpHaberDto> haberler = new ArrayList<>();

        if (result == null || !result.isArray()) {
            // content dizisi içinde text alanında JSON olabilir
            if (result != null && result.has("content")) {
                JsonNode content = result.get("content");
                if (content.isArray()) {
                    for (JsonNode item : content) {
                        if (item.has("text")) {
                            parseTextItem(item.get("text").asText(), stockCode, haberler, limit);
                        }
                        if (haberler.size() >= limit) break;
                    }
                }
            }
            return haberler;
        }

        for (JsonNode item : result) {
            if (haberler.size() >= limit) break;

            KapMcpHaberDto dto = KapMcpHaberDto.builder()
                    .baslik(getTextSafe(item, "baslik"))
                    .ozet(getTextSafe(item, "ozet"))
                    .tarih(getTextSafe(item, "tarih"))
                    .bildirimTipi(getTextSafe(item, "bildirim_tipi"))
                    .hisseSenediKodu(stockCode)
                    .chunkIds(parseChunkIdsFromItem(item))
                    .build();
            haberler.add(dto);
        }

        return haberler;
    }

    /**
     * MCP text yanıtını parse ederek haber listesine ekler.
     *
     * @param text      MCP text yanıtı
     * @param stockCode hisse kodu
     * @param haberler  hedef liste
     * @param limit     maksimum sonuç
     */
    private void parseTextItem(String text, String stockCode,
                               List<KapMcpHaberDto> haberler, int limit) {
        if (text == null || text.isBlank()) return;

        // MCP sonuçları genellikle düz metin döner; basit parse
        String[] satirlar = text.split("\n");
        for (String satir : satirlar) {
            if (haberler.size() >= limit) break;
            satir = satir.trim();
            if (satir.isEmpty()) continue;

            KapMcpHaberDto dto = KapMcpHaberDto.builder()
                    .baslik(satir.length() > 200 ? satir.substring(0, 200) : satir)
                    .hisseSenediKodu(stockCode)
                    .chunkIds(Collections.emptyList())
                    .build();
            haberler.add(dto);
        }
    }

    /**
     * Chunk içeriklerini birleştirir.
     *
     * @param result MCP chunk yükleme sonucu
     * @return birleştirilmiş metin
     */
    private String parseChunkIcerikleri(JsonNode result) {
        if (result == null) return "";

        StringBuilder sb = new StringBuilder();

        if (result.has("content") && result.get("content").isArray()) {
            for (JsonNode item : result.get("content")) {
                if (item.has("text")) {
                    if (sb.length() > 0) sb.append("\n\n");
                    sb.append(item.get("text").asText());
                }
            }
        } else if (result.isTextual()) {
            sb.append(result.asText());
        }

        return sb.toString();
    }

    /**
     * MCP arama sonucundan chunk ID'lerini çıkarır.
     *
     * @param result MCP arama sonucu
     * @return chunk ID listesi
     */
    private List<String> parseChunkIds(JsonNode result) {
        List<String> ids = new ArrayList<>();
        if (result == null) return ids;

        if (result.has("content") && result.get("content").isArray()) {
            for (JsonNode item : result.get("content")) {
                if (item.has("text")) {
                    // Text içinden chunk_id satırlarını parse et
                    String text = item.get("text").asText();
                    if (text.contains("chunk_id")) {
                        // Basit parse: satır bazlı chunk_id çıkar
                        for (String line : text.split("\n")) {
                            line = line.trim();
                            if (line.startsWith("chunk_id:")) {
                                String id = line.substring("chunk_id:".length()).trim();
                                if (!id.isEmpty()) ids.add(id);
                            }
                        }
                    }
                }
            }
        }

        if (result.isArray()) {
            for (JsonNode item : result) {
                if (item.has("chunk_id")) {
                    ids.add(item.get("chunk_id").asText());
                }
            }
        }

        return ids;
    }

    /**
     * Tek bir JSON item'dan chunk ID listesini çıkarır.
     *
     * @param item JSON nodu
     * @return chunk ID listesi
     */
    private List<String> parseChunkIdsFromItem(JsonNode item) {
        List<String> ids = new ArrayList<>();
        if (item == null) return ids;

        if (item.has("chunk_ids") && item.get("chunk_ids").isArray()) {
            for (JsonNode idNode : item.get("chunk_ids")) {
                ids.add(idNode.asText());
            }
        } else if (item.has("chunk_id")) {
            ids.add(item.get("chunk_id").asText());
        }

        return ids;
    }

    /**
     * JSON node'dan güvenli text okuma.
     *
     * @param node  JSON nodu
     * @param field alan adı
     * @return alan değeri veya null
     */
    private String getTextSafe(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText();
    }
}
