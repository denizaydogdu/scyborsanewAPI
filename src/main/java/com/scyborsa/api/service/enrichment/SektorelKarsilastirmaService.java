package com.scyborsa.api.service.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scyborsa.api.dto.fintables.FinansalOranDto;
import com.scyborsa.api.dto.fintables.SektorelKarsilastirmaDto;
import com.scyborsa.api.dto.sector.SectorSummaryDto;
import com.scyborsa.api.service.SectorService;
import com.scyborsa.api.service.client.FintablesMcpClient;
import com.scyborsa.api.service.client.FintablesMcpTokenStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Sektörel oran karşılaştırma servisi.
 *
 * <p>Bir hissenin finansal oranlarını (F/K, PD/DD, ROE vb.) aynı sektördeki
 * diğer hisselerle karşılaştırarak sektörel konumunu belirler.</p>
 *
 * <p>Veri kaynakları:</p>
 * <ul>
 *   <li>{@link FinansalOranService} — Şirket ve sektördeki hisselerin finansal oranları</li>
 *   <li>{@link SectorService} — Hissenin sektör bilgisi</li>
 * </ul>
 *
 * <p>Pozisyon belirleme kuralları:</p>
 * <ul>
 *   <li>{@code < medyan * 0.7} → UCUZ</li>
 *   <li>{@code > medyan * 1.3} → PAHALI</li>
 *   <li>Arada → ORTADA</li>
 * </ul>
 *
 * @see SektorelKarsilastirmaDto
 * @see FinansalOranService
 * @see SectorService
 */
@Slf4j
@Service
public class SektorelKarsilastirmaService {

    /** UCUZ eşik çarpanı (medyanın %70'i altı). */
    private static final double UCUZ_THRESHOLD = 0.7;

    /** PAHALI eşik çarpanı (medyanın %130'u üstü). */
    private static final double PAHALI_THRESHOLD = 1.3;

    /** Karşılaştırma yapılacak oran adları. */
    private static final List<String> KARSILASTIRMA_ORANLARI = List.of(
            "F/K", "PD/DD", "ROE", "ROA", "Cari Oran", "Brüt Kar Marjı", "Net Kar Marjı"
    );

    /** Kurumsal bilgi kartından sektör bilgisi parse etmek için regex. */
    private static final Pattern SEKTOR_PATTERN = Pattern.compile("\\*\\*Sektör:\\*\\*\\s*(.+)");

    /** Finansal oran servisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private FinansalOranService finansalOranService;

    /** Sektör servisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private SectorService sectorService;

    /** Fintables MCP istemcisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private FintablesMcpClient fintablesMcpClient;

    /** Fintables MCP token deposu. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private FintablesMcpTokenStore fintablesMcpTokenStore;

    /** JSON işlemleri için ObjectMapper. */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Belirtilen hisse için sektörel oran karşılaştırması yapar.
     *
     * <p>Akış:</p>
     * <ol>
     *   <li>Hissenin ait olduğu sektörü bulur</li>
     *   <li>Aynı sektördeki tüm hisselerin finansal oranlarını alır</li>
     *   <li>F/K, PD/DD, ROE için sektör ortalama ve medyan hesaplar</li>
     *   <li>Şirketin oranını sektöre göre konumlar</li>
     * </ol>
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return sektörel karşılaştırma DTO'su, hesaplanamazsa minimal DTO
     */
    public SektorelKarsilastirmaDto getSektorelKarsilastirma(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) {
            log.warn("[SEKTOREL-KARSILASTIRMA] stockCode boş");
            return SektorelKarsilastirmaDto.builder().build();
        }

        String code = stockCode.toUpperCase().trim();
        SektorelKarsilastirmaDto.SektorelKarsilastirmaDtoBuilder builder =
                SektorelKarsilastirmaDto.builder().hisseSenediKodu(code);

        try {
            // 1. Hissenin sektörünü bul (displayName + slug)
            String[] sektorBilgisi = findSectorForStockWithSlug(code);
            if (sektorBilgisi == null) {
                log.debug("[SEKTOREL-KARSILASTIRMA] Sektör bulunamadı: {}", code);
                return builder.build();
            }
            String sektorAdi = sektorBilgisi[0];
            String sektorSlug = sektorBilgisi[1]; // null olabilir (MCP fallback)
            builder.sektor(sektorAdi);

            // 2. Sektördeki hisselerin listesini al (slug varsa slug ile, yoksa displayName ile)
            List<String> sektorHisseleri = findSectorStocks(sektorAdi, sektorSlug);
            if (sektorHisseleri.isEmpty()) {
                log.debug("[SEKTOREL-KARSILASTIRMA] Sektörde hisse bulunamadı: sektor={}", sektorAdi);
                return builder.build();
            }

            // 3. Bulk cache'den tüm finansal oranları al (sektör karşılaştırması için)
            List<FinansalOranDto> tumOranlar = finansalOranService != null
                    ? finansalOranService.getFinansalOranlar()
                    : Collections.emptyList();

            // 4. Şirketin oranlarını al (hisse bazlı MCP fallback dahil)
            List<FinansalOranDto> sirketOranListesi = finansalOranService != null
                    ? finansalOranService.getHisseOranlar(code)
                    : Collections.emptyList();

            // En son dönemi bul: önce şirket verisinden, yoksa bulk cache'den
            List<FinansalOranDto> donemKaynagi = !sirketOranListesi.isEmpty() ? sirketOranListesi : tumOranlar;
            if (donemKaynagi.isEmpty()) {
                log.debug("[SEKTOREL-KARSILASTIRMA] Finansal oran verisi yok");
                return builder.build();
            }

            int[] sonDonem = findLatestPeriod(donemKaynagi);
            if (sonDonem == null) {
                return builder.build();
            }
            int yil = sonDonem[0], ay = sonDonem[1];

            // Şirketin kendi oranlarını dönem bazlı çıkar
            Map<String, Double> sirketOranlari = new LinkedHashMap<>();
            for (String oranAdi : KARSILASTIRMA_ORANLARI) {
                Double deger = findOranValue(sirketOranListesi, code, yil, ay, oranAdi);
                if (deger != null) {
                    sirketOranlari.put(oranAdi, Math.round(deger * 100.0) / 100.0);
                }
            }
            builder.sirketOranlari(sirketOranlari);

            // 5. Sektör hisselerinin oranlarını MCP'den çek (bulk cache yetersiz)
            Map<String, List<FinansalOranDto>> sektorOranMap = fetchSektorOranlarFromMcp(sektorHisseleri, yil, ay);

            // MCP boş döndüyse bulk cache'den fallback dene
            if (sektorOranMap.isEmpty() && !tumOranlar.isEmpty()) {
                log.debug("[SEKTOREL-KARSILASTIRMA] MCP boş, bulk cache fallback deneniyor");
                for (String hisse : sektorHisseleri) {
                    List<FinansalOranDto> hisseOranlar = tumOranlar.stream()
                            .filter(o -> hisse.equalsIgnoreCase(o.getHisseSenediKodu()))
                            .collect(Collectors.toList());
                    if (!hisseOranlar.isEmpty()) {
                        sektorOranMap.put(hisse, hisseOranlar);
                    }
                }
            }

            Map<String, Double> sektorOrtalama = new LinkedHashMap<>();
            Map<String, Double> sektorMedian = new LinkedHashMap<>();
            Map<String, String> pozisyon = new LinkedHashMap<>();

            for (String oranAdi : KARSILASTIRMA_ORANLARI) {
                List<Double> sektorDegerleri = new ArrayList<>();

                for (String hisseKodu : sektorHisseleri) {
                    List<FinansalOranDto> hisseOranlar = sektorOranMap.getOrDefault(hisseKodu, Collections.emptyList());
                    Double deger = findOranValue(hisseOranlar, hisseKodu, yil, ay, oranAdi);
                    if (deger != null && Double.isFinite(deger)) {
                        sektorDegerleri.add(deger);
                    }
                }

                if (sektorDegerleri.size() < 2) {
                    continue; // Yeterli veri yok
                }

                // Ortalama
                double avg = sektorDegerleri.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                sektorOrtalama.put(oranAdi, Math.round(avg * 100.0) / 100.0);

                // Medyan
                Collections.sort(sektorDegerleri);
                double median = calculateMedian(sektorDegerleri);
                sektorMedian.put(oranAdi, Math.round(median * 100.0) / 100.0);

                // Pozisyon belirleme
                Double sirketDeger = sirketOranlari.get(oranAdi);
                if (sirketDeger != null && median != 0) {
                    pozisyon.put(oranAdi, determinePozisyon(sirketDeger, median, oranAdi));
                }
            }

            builder.sektorOrtalama(sektorOrtalama);
            builder.sektorMedian(sektorMedian);
            builder.pozisyon(pozisyon);

            log.debug("[SEKTOREL-KARSILASTIRMA] {} sektör={}, {} oran karşılaştırıldı",
                    code, sektorAdi, pozisyon.size());

        } catch (Exception e) {
            log.warn("[SEKTOREL-KARSILASTIRMA] Hesaplama hatası: stockCode={}", code, e);
        }

        return builder.build();
    }

    // ==================== Sektör Oran Çekme ====================

    /** Hisse kodu doğrulama regex'i (SQL injection koruması). */
    private static final Pattern STOCK_CODE_PATTERN = Pattern.compile("^[A-Z0-9]{1,10}$");

    /**
     * Sektördeki hisselerin finansal oranlarını MCP üzerinden tek SQL ile çeker.
     *
     * <p>Bulk cache LIMIT 5000'de çoğu hisse bulunmadığı için, sektör hisselerini
     * doğrudan {@code WHERE ... IN (...)} SQL sorgusu ile alır.</p>
     *
     * <p>SQL injection koruması: hisse kodları regex ile doğrulanır (SectorService kaynağı
     * güvenilir olsa bile).</p>
     *
     * @param hisseler sektördeki hisse kodu listesi
     * @param yil      dönem yılı
     * @param ay       dönem ayı
     * @return hisse kodu → finansal oranlar map'i, hata durumunda boş map
     */
    private Map<String, List<FinansalOranDto>> fetchSektorOranlarFromMcp(List<String> hisseler, int yil, int ay) {
        if (fintablesMcpClient == null || fintablesMcpTokenStore == null || !fintablesMcpTokenStore.isTokenValid()) {
            return Collections.emptyMap();
        }

        if (hisseler == null || hisseler.isEmpty()) {
            return Collections.emptyMap();
        }

        try {
            // Hisse kodlarını doğrula ve max 50 ile sınırla
            List<String> validated = hisseler.stream()
                    .filter(Objects::nonNull)
                    .map(h -> h.trim().toUpperCase())
                    .filter(h -> STOCK_CODE_PATTERN.matcher(h).matches())
                    .limit(50)
                    .collect(Collectors.toList());

            if (validated.isEmpty()) {
                return Collections.emptyMap();
            }

            String inClause = validated.stream()
                    .map(h -> "'" + h + "'")
                    .collect(Collectors.joining(","));

            String sql = "SELECT hisse_senedi_kodu, satir_no, kategori, oran, deger " +
                    "FROM hisse_finansal_tablolari_finansal_oranlari " +
                    "WHERE hisse_senedi_kodu IN (" + inClause + ") " +
                    "AND yil = " + yil + " AND ay = " + ay + " " +
                    "ORDER BY hisse_senedi_kodu, satir_no LIMIT 2000";

            JsonNode result = fintablesMcpClient.veriSorgula(sql, "Sektör oranları (" + validated.size() + " hisse)");
            if (result == null) {
                return Collections.emptyMap();
            }

            String responseText = extractMcpResponseText(result);
            if (responseText == null || responseText.isBlank()) {
                return Collections.emptyMap();
            }

            List<FinansalOranDto> allOranlar = parseSektorOranMarkdownTable(responseText, yil, ay);
            log.debug("[SEKTOREL-KARSILASTIRMA] MCP sektör oranları: {} hisse için {} satır alındı",
                    validated.size(), allOranlar.size());

            // Hisse bazlı grupla
            return allOranlar.stream()
                    .filter(o -> o.getHisseSenediKodu() != null)
                    .collect(Collectors.groupingBy(FinansalOranDto::getHisseSenediKodu));

        } catch (Exception e) {
            log.warn("[SEKTOREL-KARSILASTIRMA] MCP sektör oran sorgusu hatası", e);
            return Collections.emptyMap();
        }
    }

    /**
     * MCP JSON-RPC yanıtından text alanını çıkarır.
     *
     * @param result MCP yanıtı
     * @return text içeriği veya {@code null}
     */
    private String extractMcpResponseText(JsonNode result) {
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
            log.warn("[SEKTOREL-KARSILASTIRMA] Response text çıkarma hatası", e);
            return null;
        }
    }

    /**
     * MCP markdown tablo yanıtını parse ederek {@link FinansalOranDto} listesine dönüştürür.
     *
     * <p>Kolon sırası: hisse_senedi_kodu, satir_no, kategori, oran, deger.
     * JSON sarmalı veya düz markdown formatı desteklenir.</p>
     *
     * @param rawText MCP yanıt metni
     * @param yil     dönem yılı (parse'dan gelmeyen alanlar için)
     * @param ay      dönem ayı (parse'dan gelmeyen alanlar için)
     * @return parse edilmiş DTO listesi
     */
    private List<FinansalOranDto> parseSektorOranMarkdownTable(String rawText, int yil, int ay) {
        List<FinansalOranDto> result = new ArrayList<>();
        if (rawText == null || rawText.isBlank()) return result;

        try {
            // JSON sarmalını çöz
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
            if (lines.length < 3) return result;

            // Header'dan kolon indekslerini bul
            String[] headers = parseMdRow(lines[0]);
            int idxKod = findColumnIndex(headers, "hisse_senedi_kodu");
            int idxSatirNo = findColumnIndex(headers, "satir_no");
            int idxKategori = findColumnIndex(headers, "kategori");
            int idxOran = findColumnIndex(headers, "oran");
            int idxDeger = findColumnIndex(headers, "deger");

            for (int i = 2; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty() || line.replaceAll("[|:\\-\\s]", "").isEmpty()) continue;

                String[] cols = parseMdRow(line);
                if (cols.length == 0) continue;

                try {
                    FinansalOranDto dto = FinansalOranDto.builder()
                            .hisseSenediKodu(safeGet(cols, idxKod))
                            .yil(yil)
                            .ay(ay)
                            .satirNo(parseInteger(safeGet(cols, idxSatirNo)))
                            .kategori(safeGet(cols, idxKategori))
                            .oran(safeGet(cols, idxOran))
                            .deger(parseDouble(safeGet(cols, idxDeger)))
                            .build();
                    result.add(dto);
                } catch (Exception e) {
                    // Tek satır hatası diğerlerini etkilemesin
                }
            }
        } catch (Exception e) {
            log.warn("[SEKTOREL-KARSILASTIRMA] Sektör oran tablosu parse hatası", e);
        }

        return result;
    }

    // ==================== Yardımcı Metodlar ====================

    /**
     * Hissenin ait olduğu sektör bilgisini bulur (displayName + slug).
     *
     * <p>İki aşamalı arama stratejisi:</p>
     * <ol>
     *   <li><b>Hızlı yol:</b> {@link SectorService#findSectorForStock(String)} —
     *       TradingView taramasından oluşturulan stockToSectorMap üzerinden O(1) lookup.
     *       Tüm ~700 BIST hissesini kapsar.</li>
     *   <li><b>Yavaş yol (MCP fallback):</b> Fintables MCP {@code dokumanlarda_ara} ile
     *       kurumsal bilgi kartından sektör bilgisi parse eder.</li>
     * </ol>
     *
     * @param stockCode hisse kodu (ör: "GARAN", "A1CAP")
     * @return {@code [displayName, slug]} dizisi — slug MCP fallback'te {@code null} olabilir;
     *         bulunamazsa {@code null}
     */
    private String[] findSectorForStockWithSlug(String stockCode) {
        // 1. Hızlı yol: SectorService stockToSectorMap (TradingView taramasından)
        if (sectorService != null) {
            try {
                SectorService.StockSectorInfo info = sectorService.findSectorForStock(stockCode);
                if (info != null) {
                    log.debug("[SEKTOREL-KARSILASTIRMA] Sektör bulundu (stockToSectorMap): {}→{}",
                            stockCode, info.displayName());
                    return new String[]{info.displayName(), info.slug()};
                }
            } catch (Exception e) {
                log.debug("[SEKTOREL-KARSILASTIRMA] stockToSectorMap arama hatası: {}", stockCode, e);
            }
        }

        // 2. Yavaş yol (MCP fallback): Fintables kurumsal bilgi kartından sektör bilgisi
        String mcpSektor = findSectorFromMcp(stockCode);
        if (mcpSektor != null) {
            return new String[]{mcpSektor, null};
        }

        log.debug("[SEKTOREL-KARSILASTIRMA] Hisse sektörü bulunamadı: {}", stockCode);
        return null;
    }

    /**
     * Fintables MCP kurumsal bilgi kartından sektör bilgisini alır.
     *
     * <p>MCP {@code dokumanlarda_ara} ile kurumsal bilgi kartını arar,
     * chunk içeriğinden {@code **Sektör:** XXX} satırını regex ile parse eder.</p>
     *
     * <p>MCP token geçersizse veya herhangi bir hata oluşursa graceful degradation
     * ile {@code null} döner.</p>
     *
     * @param stockCode hisse kodu (ör: "A1CAP")
     * @return sektör adı (ör: "MALİ KURULUŞLAR / ARACI KURUMLAR") veya {@code null}
     */
    private String findSectorFromMcp(String stockCode) {
        if (fintablesMcpClient == null || fintablesMcpTokenStore == null) {
            return null;
        }

        if (!fintablesMcpTokenStore.isTokenValid()) {
            log.debug("[SEKTOREL-KARSILASTIRMA] MCP token geçersiz, sektör araması atlanıyor: {}", stockCode);
            return null;
        }

        try {
            // Kurumsal bilgi kartını ara
            String filter = "dokuman_tipi = \"kurumsal_bilgi_karti\" AND iliskili_semboller = \""
                    + stockCode.toUpperCase().trim() + "\"";
            JsonNode searchResult = fintablesMcpClient.dokumanlardaAra(
                    "Sektör bilgisi: " + stockCode, "", filter, 1);

            if (searchResult == null) {
                return null;
            }

            // Arama sonucundan chunk ID'leri çıkar
            List<String> chunkIds = extractChunkIds(searchResult);
            if (chunkIds.isEmpty()) {
                log.debug("[SEKTOREL-KARSILASTIRMA] MCP'de kurumsal bilgi kartı bulunamadı: {}", stockCode);
                return null;
            }

            // Chunk içeriklerini yükle
            JsonNode chunkResult = fintablesMcpClient.dokumanChunkYukle(chunkIds);
            if (chunkResult == null) {
                return null;
            }

            // Chunk content'ten sektör bilgisini parse et
            String sektor = parseSektorFromChunks(chunkResult);
            if (sektor != null) {
                log.debug("[SEKTOREL-KARSILASTIRMA] Sektör bulundu (MCP): {}→{}", stockCode, sektor);
            }
            return sektor;

        } catch (Exception e) {
            log.warn("[SEKTOREL-KARSILASTIRMA] MCP sektör araması hatası: {}", stockCode, e);
            return null;
        }
    }

    /**
     * MCP arama sonucundan chunk ID listesini çıkarır.
     *
     * <p>JSON-RPC result içindeki content dizisinden text alanlarını okuyarak
     * chunk ID'lerini toplar.</p>
     *
     * @param searchResult MCP {@code dokumanlarda_ara} sonucu
     * @return chunk ID listesi, yoksa boş liste
     */
    private List<String> extractChunkIds(JsonNode searchResult) {
        List<String> ids = new ArrayList<>();
        try {
            // result → content dizisi
            JsonNode content = searchResult.path("content");
            if (content.isArray()) {
                for (JsonNode item : content) {
                    String text = item.path("text").asText("");
                    if (!text.isBlank()) {
                        // Text genellikle JSON formatında, chunk_id alanlarını içerir
                        JsonNode parsed = objectMapper.readTree(text);
                        if (parsed.isArray()) {
                            for (JsonNode doc : parsed) {
                                String chunkId = doc.path("chunk_id").asText(null);
                                if (chunkId == null) {
                                    chunkId = doc.path("id").asText(null);
                                }
                                if (chunkId != null) {
                                    ids.add(chunkId);
                                }
                            }
                        } else if (parsed.isObject()) {
                            String chunkId = parsed.path("chunk_id").asText(null);
                            if (chunkId == null) {
                                chunkId = parsed.path("id").asText(null);
                            }
                            if (chunkId != null) {
                                ids.add(chunkId);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[SEKTOREL-KARSILASTIRMA] Chunk ID parse hatası", e);
        }
        return ids;
    }

    /**
     * MCP chunk içeriklerinden {@code **Sektör:** XXX} satırını parse eder.
     *
     * @param chunkResult MCP {@code dokuman_chunk_yukle} sonucu
     * @return sektör adı veya {@code null}
     */
    private String parseSektorFromChunks(JsonNode chunkResult) {
        try {
            JsonNode content = chunkResult.path("content");
            if (content.isArray()) {
                for (JsonNode item : content) {
                    String text = item.path("text").asText("");
                    Matcher matcher = SEKTOR_PATTERN.matcher(text);
                    if (matcher.find()) {
                        return matcher.group(1).trim();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[SEKTOREL-KARSILASTIRMA] Sektör parse hatası", e);
        }
        return null;
    }

    /**
     * Belirtilen sektördeki hisse kodlarını bulur.
     *
     * <p>Slug mevcutsa doğrudan {@link SectorService#getSectorStocks(String)} ile alır.
     * Slug yoksa (MCP fallback durumu) displayName ile eşleşen sektörün slug'ını bulur.</p>
     *
     * @param sektorAdi  sektör displayName
     * @param sektorSlug sektör slug'ı (nullable — MCP fallback'te null olabilir)
     * @return hisse kodu listesi, yoksa boş liste
     */
    private List<String> findSectorStocks(String sektorAdi, String sektorSlug) {
        if (sectorService == null) {
            return Collections.emptyList();
        }

        try {
            // Slug varsa doğrudan kullan
            String slug = sektorSlug;

            // Slug yoksa displayName ile eşleşen sektörün slug'ını bul
            if (slug == null) {
                List<SectorSummaryDto> summaries = sectorService.getSectorSummaries();
                for (SectorSummaryDto summary : summaries) {
                    if (sektorAdi.equalsIgnoreCase(summary.getDisplayName())) {
                        slug = summary.getSlug();
                        break;
                    }
                }
            }

            if (slug != null) {
                var stocks = sectorService.getSectorStocks(slug);
                if (stocks != null) {
                    return stocks.stream()
                            .map(s -> s.getTicker())
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
                }
            }
        } catch (Exception e) {
            log.warn("[SEKTOREL-KARSILASTIRMA] Sektör hisse listesi hatası: sektor={}", sektorAdi, e);
        }

        return Collections.emptyList();
    }

    /**
     * Finansal oran listesinden en son dönemi bulur.
     *
     * @param oranlar finansal oran listesi
     * @return {@code [yıl, ay]} dizisi veya {@code null}
     */
    private int[] findLatestPeriod(List<FinansalOranDto> oranlar) {
        return oranlar.stream()
                .filter(d -> d.getYil() != null && d.getAy() != null)
                .max(Comparator.comparingInt((FinansalOranDto d) -> d.getYil() * 100 + d.getAy()))
                .map(d -> new int[]{d.getYil(), d.getAy()})
                .orElse(null);
    }

    /**
     * Belirtilen hisse, dönem ve oran adı için değer arar.
     *
     * @param oranlar    tüm finansal oranlar
     * @param stockCode  hisse kodu
     * @param yil        dönem yılı
     * @param ay         dönem ayı
     * @param oranAdi    oran adı
     * @return oran değeri veya {@code null}
     */
    private Double findOranValue(List<FinansalOranDto> oranlar, String stockCode,
                                 int yil, int ay, String oranAdi) {
        return oranlar.stream()
                .filter(o -> stockCode.equalsIgnoreCase(o.getHisseSenediKodu()))
                .filter(o -> o.getYil() != null && o.getYil() == yil
                        && o.getAy() != null && o.getAy() == ay)
                .filter(o -> o.getOran() != null && o.getDeger() != null)
                .filter(o -> o.getOran().equalsIgnoreCase(oranAdi)
                        || o.getOran().toLowerCase().contains(oranAdi.toLowerCase()))
                .map(FinansalOranDto::getDeger)
                .findFirst()
                .orElse(null);
    }

    /**
     * Sıralı liste üzerinden medyan hesaplar.
     *
     * @param sorted sıralı değer listesi (boş olmamalı)
     * @return medyan değeri
     */
    private double calculateMedian(List<Double> sorted) {
        int size = sorted.size();
        if (size == 0) return 0.0;
        if (size % 2 == 1) {
            return sorted.get(size / 2);
        }
        return (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
    }

    /**
     * Şirketin oranını sektör medyanına göre konumlar.
     *
     * <p>Valuation oranları (F/K, PD/DD): düşük = "UCUZ", yüksek = "PAHALI".</p>
     * <p>Karlılık/kalite oranları (ROE, ROA, Brüt Kar Marjı, Net Kar Marjı, Cari Oran):
     * yüksek = "GÜÇLÜ", düşük = "ZAYIF".</p>
     *
     * @param sirketDeger şirketin oran değeri
     * @param median      sektör medyanı
     * @param oranAdi     oran adı (yorumlama yönü için)
     * @return valuation için "UCUZ"/"ORTADA"/"PAHALI", karlılık için "GÜÇLÜ"/"ORTADA"/"ZAYIF"
     */
    private String determinePozisyon(double sirketDeger, double median, String oranAdi) {
        // Karlılık/kalite metrikleri: yüksek = güçlü, düşük = zayıf
        Set<String> karlilikOranlar = Set.of("roe", "roa", "brüt", "net kar", "cari");
        String lowerOran = oranAdi.toLowerCase();
        boolean isKarlilik = karlilikOranlar.stream().anyMatch(lowerOran::contains);

        if (isKarlilik) {
            // Yüksek olan güçlü
            if (sirketDeger > median * PAHALI_THRESHOLD) return "GÜÇLÜ";
            if (sirketDeger < median * UCUZ_THRESHOLD) return "ZAYIF";
            return "ORTADA";
        }

        // Valuation oranları (F/K, PD/DD): düşük olan ucuz
        if (sirketDeger < median * UCUZ_THRESHOLD) return "UCUZ";
        if (sirketDeger > median * PAHALI_THRESHOLD) return "PAHALI";
        return "ORTADA";
    }

    // ==================== Markdown Tablo Parse Yardımcıları ====================

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
}
