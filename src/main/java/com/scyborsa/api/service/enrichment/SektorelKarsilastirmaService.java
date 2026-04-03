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

            // 5. Sektör oranlarını sadece bulk cache'den hesapla
            // (Cache'de yoksa sektör kısmı boş kalır — kabul edilebilir)
            Map<String, Double> sektorOrtalama = new LinkedHashMap<>();
            Map<String, Double> sektorMedian = new LinkedHashMap<>();
            Map<String, String> pozisyon = new LinkedHashMap<>();

            for (String oranAdi : KARSILASTIRMA_ORANLARI) {
                List<Double> sektorDegerleri = new ArrayList<>();

                for (String hisseKodu : sektorHisseleri) {
                    Double deger = findOranValue(tumOranlar, hisseKodu, yil, ay, oranAdi);
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
}
