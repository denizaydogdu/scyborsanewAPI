package com.scyborsa.api.service.enrichment;

import com.scyborsa.api.dto.fintables.FinansalOranDto;
import com.scyborsa.api.dto.fintables.SektorelKarsilastirmaDto;
import com.scyborsa.api.dto.sector.SectorSummaryDto;
import com.scyborsa.api.service.SectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
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

    /** Finansal oran servisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private FinansalOranService finansalOranService;

    /** Sektör servisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private SectorService sectorService;

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
            // 1. Hissenin sektörünü bul
            String sektorAdi = findSectorForStock(code);
            if (sektorAdi == null) {
                log.debug("[SEKTOREL-KARSILASTIRMA] Sektör bulunamadı: {}", code);
                return builder.build();
            }
            builder.sektor(sektorAdi);

            // 2. Sektördeki hisselerin listesini al
            List<String> sektorHisseleri = findSectorStocks(sektorAdi);
            if (sektorHisseleri.isEmpty()) {
                log.debug("[SEKTOREL-KARSILASTIRMA] Sektörde hisse bulunamadı: sektor={}", sektorAdi);
                return builder.build();
            }

            // 3. Tüm finansal oranları al
            List<FinansalOranDto> tumOranlar = finansalOranService != null
                    ? finansalOranService.getFinansalOranlar()
                    : Collections.emptyList();

            if (tumOranlar.isEmpty()) {
                log.debug("[SEKTOREL-KARSILASTIRMA] Finansal oran verisi yok");
                return builder.build();
            }

            // En son dönemi bul (tüm piyasa için)
            int[] sonDonem = findLatestPeriod(tumOranlar);
            if (sonDonem == null) {
                return builder.build();
            }
            int yil = sonDonem[0], ay = sonDonem[1];

            // 4. Şirketin oranlarını al
            Map<String, Double> sirketOranlari = new LinkedHashMap<>();
            for (String oranAdi : KARSILASTIRMA_ORANLARI) {
                Double deger = findOranValue(tumOranlar, code, yil, ay, oranAdi);
                if (deger != null) {
                    sirketOranlari.put(oranAdi, Math.round(deger * 100.0) / 100.0);
                }
            }
            builder.sirketOranlari(sirketOranlari);

            // 5. Sektör oranlarını topla ve istatistik hesapla
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
     * Hissenin ait olduğu sektör adını bulur.
     *
     * <p>SectorService üzerinden sektör özetlerini tarayarak hissenin
     * bulunduğu sektörü belirler.</p>
     *
     * @param stockCode hisse kodu
     * @return sektör displayName veya {@code null}
     */
    private String findSectorForStock(String stockCode) {
        if (sectorService == null) {
            return null;
        }

        try {
            List<SectorSummaryDto> summaries = sectorService.getSectorSummaries();
            for (SectorSummaryDto summary : summaries) {
                if (summary.getTopStocks() != null) {
                    for (SectorSummaryDto.TopStockInfo stock : summary.getTopStocks()) {
                        if (stockCode.equalsIgnoreCase(stock.getTicker())) {
                            return summary.getDisplayName();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[SEKTOREL-KARSILASTIRMA] Sektör arama hatası: {}", stockCode, e);
        }

        // Top-3'te bulunamazsa sektör karşılaştırması yapılamaz
        // (44 sektörü sırayla taramak çok yavaş, performans sorunu)
        log.debug("[SEKTOREL-KARSILASTIRMA] Hisse sektörü bulunamadı (top-3'te yok): {}", stockCode);
        return null;
    }

    /**
     * Belirtilen sektördeki hisse kodlarını bulur.
     *
     * @param sektorAdi sektör displayName
     * @return hisse kodu listesi, yoksa boş liste
     */
    private List<String> findSectorStocks(String sektorAdi) {
        if (sectorService == null) {
            return Collections.emptyList();
        }

        try {
            List<SectorSummaryDto> summaries = sectorService.getSectorSummaries();
            for (SectorSummaryDto summary : summaries) {
                if (sektorAdi.equalsIgnoreCase(summary.getDisplayName())) {
                    var stocks = sectorService.getSectorStocks(summary.getSlug());
                    if (stocks != null) {
                        return stocks.stream()
                                .map(s -> s.getTicker())
                                .filter(Objects::nonNull)
                                .collect(Collectors.toList());
                    }
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
