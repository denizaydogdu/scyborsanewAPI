package com.scyborsa.api.service.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.dto.fintables.KapHaberSinyalDto;
import com.scyborsa.api.service.client.FintablesMcpClient;
import com.scyborsa.api.service.client.FintablesMcpTokenStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * KAP haber sinyal servisi.
 *
 * <p>Son 30 günün KAP haberlerini Fintables MCP {@code dokumanlarda_ara} tool'u
 * ile çeker ve keyword bazlı sınıflandırma ile sinyaller üretir.</p>
 *
 * <p>Haber tipleri ve keyword'ler:</p>
 * <ul>
 *   <li>SERMAYE_ARTIRIMI — "sermaye artırımı", "bedelsiz", "bedelli"</li>
 *   <li>TEMETTU — "temettü", "kar payı"</li>
 *   <li>GERI_ALIM — "geri alım"</li>
 *   <li>ORTAKLIK_DEGISIKLIGI — "ortaklık", "pay devri"</li>
 * </ul>
 *
 * @see KapHaberSinyalDto
 */
@Slf4j
@Service
public class KapHaberSinyalService {

    /** Istanbul saat dilimi. */
    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");

    /** StockCode regex doğrulama (SQL injection koruması). */
    private static final Pattern STOCK_CODE_PATTERN = Pattern.compile("^[A-Z0-9]{1,10}$");

    /** JSON parser (Spring Boot auto-configured). */
    @Autowired
    private ObjectMapper objectMapper;

    /** Tarih çıkarma regex (yyyy-MM-dd veya dd.MM.yyyy formatları). */
    private static final Pattern DATE_PATTERN_ISO = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern DATE_PATTERN_TR = Pattern.compile("(\\d{2}\\.\\d{2}\\.\\d{4})");

    /** Fintables MCP istemcisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private FintablesMcpClient mcpClient;

    /** MCP token saklama bileşeni. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private FintablesMcpTokenStore tokenStore;

    /**
     * Belirtilen hisse için son 30 günün KAP haber sinyallerini üretir.
     *
     * <p>MCP {@code dokumanlarda_ara} tool'u ile haberleri çeker, keyword bazlı
     * sınıflandırma yapar ve sinyal listesi olarak döndürür.</p>
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return KAP haber sinyal listesi, veri yoksa boş liste
     */
    public List<KapHaberSinyalDto> getKapSinyaller(String stockCode) {
        // SQL injection guard
        if (stockCode == null || !STOCK_CODE_PATTERN.matcher(stockCode).matches()) {
            log.warn("[KAP-SINYAL] Geçersiz hisse kodu: {}", stockCode);
            return Collections.emptyList();
        }

        if (mcpClient == null || tokenStore == null || !tokenStore.isTokenValid()) {
            log.debug("[KAP-SINYAL] MCP istemci veya token geçersiz, boş liste dönülüyor");
            return Collections.emptyList();
        }

        try {
            String filter = "dokuman_tipi = \"kap_haberi\" AND iliskili_semboller = \"" + stockCode + "\"";
            JsonNode result = mcpClient.dokumanlardaAra(
                    "KAP haber sinyalleri: " + stockCode, "", filter, 20);

            if (result == null) {
                return Collections.emptyList();
            }

            return parseHaberSinyalleri(stockCode, result);
        } catch (Exception e) {
            log.warn("[KAP-SINYAL] KAP haber sinyalleri alınamadı: stockCode={}", stockCode, e);
            return Collections.emptyList();
        }
    }

    /**
     * MCP doküman arama sonucunu parse ederek haber sinyallerine dönüştürür.
     *
     * <p>MCP response formatı: {@code content[0].text} içinde JSON string bulunur.
     * JSON yapısı: {@code {"toplam":N, "sonuclar":[{document_title, highlight, yayinlanma_tarihi_utc, ...}]}}</p>
     *
     * <p>Sadece son 30 gün içindeki sinyalleri döndürür. Tarih parse edilemezse
     * sinyal dahil edilir (null tarih = yeni haber kabul edilir).</p>
     *
     * @param stockCode hisse kodu
     * @param result    MCP dokumanlardaAra sonucu
     * @return sinyal listesi (son 30 gün)
     */
    private List<KapHaberSinyalDto> parseHaberSinyalleri(String stockCode, JsonNode result) {
        List<KapHaberSinyalDto> sinyaller = new ArrayList<>();
        LocalDate bugun = LocalDate.now(ISTANBUL_ZONE);
        LocalDate otuzGunOnce = bugun.minusDays(30);

        // MCP result → content[0].text → JSON string → sonuclar array
        String jsonText = extractTextFromResult(result);
        if (jsonText == null || jsonText.isBlank()) {
            return sinyaller;
        }

        try {
            JsonNode root = objectMapper.readTree(jsonText);
            JsonNode sonuclar = root.get("sonuclar");
            if (sonuclar == null || !sonuclar.isArray()) {
                return sinyaller;
            }

            for (JsonNode item : sonuclar) {
                try {
                    // document_title + highlight birleştirerek keyword analizi yap
                    String title = item.has("document_title") ? item.get("document_title").asText() : "";
                    String highlight = item.has("highlight") ? item.get("highlight").asText() : "";
                    String text = title + " " + highlight;

                    if (text.isBlank()) continue;

                    String textLower = text.toLowerCase();

                    // Tarih: yayinlanma_tarihi_utc alanından çıkar
                    String tarihStr = item.has("yayinlanma_tarihi_utc")
                            ? item.get("yayinlanma_tarihi_utc").asText() : "";
                    LocalDate haberTarihi = extractDate(tarihStr);

                    // Keyword bazlı sınıflandırma
                    siniflandir(stockCode, text, textLower,
                            haberTarihi != null ? haberTarihi : bugun, sinyaller);
                } catch (Exception e) {
                    log.debug("[KAP-SINYAL] Haber item parse hatası", e);
                }
            }
        } catch (Exception e) {
            log.warn("[KAP-SINYAL] JSON parse hatası: {}", e.getMessage());
        }

        // Son 30 gün filtresi — null tarih = yeni haber kabul et (dahil et)
        return sinyaller.stream()
                .filter(s -> s.getTarih() == null || !s.getTarih().isBefore(otuzGunOnce))
                .collect(Collectors.toList());
    }

    /**
     * Metin içinden tarih çıkarır (yyyy-MM-dd veya dd.MM.yyyy formatları).
     *
     * @param text kaynak metin
     * @return bulunan tarih, bulunamazsa {@code null}
     */
    private LocalDate extractDate(String text) {
        // ISO format: yyyy-MM-dd
        Matcher isoMatcher = DATE_PATTERN_ISO.matcher(text);
        if (isoMatcher.find()) {
            try {
                return LocalDate.parse(isoMatcher.group(1));
            } catch (Exception ignored) {
                // geçersiz tarih, diğer formata devam
            }
        }

        // TR format: dd.MM.yyyy
        Matcher trMatcher = DATE_PATTERN_TR.matcher(text);
        if (trMatcher.find()) {
            try {
                return LocalDate.parse(trMatcher.group(1), DateTimeFormatter.ofPattern("dd.MM.yyyy"));
            } catch (Exception ignored) {
                // geçersiz tarih
            }
        }

        return null;
    }

    /**
     * Keyword bazlı haber sınıflandırma ve sinyal oluşturma.
     *
     * @param stockCode hisse kodu
     * @param text      orijinal metin
     * @param textLower küçük harfli metin
     * @param tarih     sinyal tarihi
     * @param sinyaller sinyal listesi (mutable)
     */
    private void siniflandir(String stockCode, String text, String textLower,
                             LocalDate tarih, List<KapHaberSinyalDto> sinyaller) {

        String ozet = text.length() > 200 ? text.substring(0, 200) + "..." : text;

        if (textLower.contains("sermaye artırımı") || textLower.contains("bedelsiz")
                || textLower.contains("bedelli")) {
            sinyaller.add(KapHaberSinyalDto.builder()
                    .hisseSenediKodu(stockCode)
                    .haberTipi("SERMAYE_ARTIRIMI")
                    .haberOzet(ozet)
                    .sinyalYonu(textLower.contains("bedelsiz") ? "POZITIF" : "NOTR")
                    .tarih(tarih)
                    .build());
        }

        if (textLower.contains("temettü") || textLower.contains("kar payı")
                || textLower.contains("kâr payı")) {
            sinyaller.add(KapHaberSinyalDto.builder()
                    .hisseSenediKodu(stockCode)
                    .haberTipi("TEMETTU")
                    .haberOzet(ozet)
                    .sinyalYonu("POZITIF")
                    .tarih(tarih)
                    .build());
        }

        if (textLower.contains("geri alım")) {
            sinyaller.add(KapHaberSinyalDto.builder()
                    .hisseSenediKodu(stockCode)
                    .haberTipi("GERI_ALIM")
                    .haberOzet(ozet)
                    .sinyalYonu("POZITIF")
                    .tarih(tarih)
                    .build());
        }

        if (textLower.contains("ortaklık") || textLower.contains("pay devri")) {
            sinyaller.add(KapHaberSinyalDto.builder()
                    .hisseSenediKodu(stockCode)
                    .haberTipi("ORTAKLIK_DEGISIKLIGI")
                    .haberOzet(ozet)
                    .sinyalYonu("NOTR")
                    .tarih(tarih)
                    .build());
        }
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

        // result.content formatı (nested)
        if (result.has("result")) {
            JsonNode inner = result.get("result");
            if (inner.has("content") && inner.get("content").isArray()) {
                JsonNode content = inner.get("content");
                if (!content.isEmpty() && content.get(0).has("text")) {
                    return content.get(0).get("text").asText();
                }
            }
        }

        // Doğrudan text alanı
        if (result.has("text")) {
            return result.get("text").asText();
        }

        // Fallback: tüm JSON'u string olarak dön
        return result.toString();
    }
}
