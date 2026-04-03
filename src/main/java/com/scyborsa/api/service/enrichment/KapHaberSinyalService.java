package com.scyborsa.api.service.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
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

        // MCP result → content array → text alanlarından parse
        JsonNode content = extractContent(result);
        if (content == null || !content.isArray()) {
            return sinyaller;
        }

        for (JsonNode item : content) {
            try {
                String text = item.has("text") ? item.get("text").asText() : item.asText();
                if (text == null || text.isBlank()) {
                    continue;
                }

                String textLower = text.toLowerCase();

                // Hisse kodu ilgili mi kontrol et
                if (!textLower.contains(stockCode.toLowerCase())) {
                    continue;
                }

                // Metinden tarih çıkar
                LocalDate haberTarihi = extractDate(text);

                // Keyword bazlı sınıflandırma
                siniflandir(stockCode, text, textLower,
                        haberTarihi != null ? haberTarihi : bugun, sinyaller);
            } catch (Exception e) {
                log.debug("[KAP-SINYAL] Haber parse hatası", e);
            }
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
     * MCP result'tan content dizisini çıkarır.
     *
     * <p>JSON-RPC response formatı: {@code result.content[]} veya doğrudan array.</p>
     *
     * @param result MCP sonucu
     * @return content JsonNode (array), yoksa {@code null}
     */
    private JsonNode extractContent(JsonNode result) {
        if (result.isArray()) {
            return result;
        }
        if (result.has("content")) {
            return result.get("content");
        }
        if (result.has("result") && result.get("result").has("content")) {
            return result.get("result").get("content");
        }
        return null;
    }
}
