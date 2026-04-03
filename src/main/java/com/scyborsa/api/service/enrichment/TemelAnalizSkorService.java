package com.scyborsa.api.service.enrichment;

import com.scyborsa.api.dto.fintables.FinansalOranDto;
import com.scyborsa.api.dto.fintables.FinansalTabloDto;
import com.scyborsa.api.dto.fintables.TemelAnalizSkorDto;
import com.scyborsa.api.service.chart.QuotePriceCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Temel analiz skor hesaplama servisi.
 *
 * <p>Altman Z-Score, Piotroski F-Score ve Graham Sayısı hesaplamalarını
 * gerçekleştirir. Tüm hesaplamalar {@link FinansalTabloService} ve
 * {@link FinansalOranService} cache'lerinden okunan veriye dayanır.</p>
 *
 * <p>Her skor bileşeni bağımsız try-catch ile sarılıdır; bir bileşen
 * hesaplanamazsa diğerleri yine döner (graceful degradation).</p>
 *
 * @see TemelAnalizSkorDto
 * @see FinansalTabloService
 * @see FinansalOranService
 */
@Slf4j
@Service
public class TemelAnalizSkorService {

    /** Altman Z-Score güvenli bölge alt sınırı. */
    private static final double ALTMAN_SAFE_THRESHOLD = 2.99;

    /** Altman Z-Score tehlike bölgesi üst sınırı. */
    private static final double ALTMAN_DISTRESS_THRESHOLD = 1.81;

    /** Piotroski güçlü bölge alt sınırı. */
    private static final int PIOTROSKI_STRONG_THRESHOLD = 7;

    /** Piotroski zayıf bölge üst sınırı. */
    private static final int PIOTROSKI_WEAK_THRESHOLD = 3;

    /** Graham formülündeki sabit çarpan (22.5 = 15 F/K * 1.5 PD/DD). */
    private static final double GRAHAM_MULTIPLIER = 22.5;

    /** Finansal tablo servisi (bilanço, gelir tablosu, nakit akım). Bean yoksa {@code null}. */
    @Autowired(required = false)
    private FinansalTabloService finansalTabloService;

    /** Finansal oran servisi (F/K, PD/DD, ROE vb.). Bean yoksa {@code null}. */
    @Autowired(required = false)
    private FinansalOranService finansalOranService;

    /** Anlık fiyat cache'i. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private QuotePriceCache quotePriceCache;

    /**
     * Belirtilen hisse için temel analiz skorlarını hesaplar.
     *
     * <p>Altman Z-Score, Piotroski F-Score ve Graham Sayısı bağımsız olarak
     * hesaplanır. Hesaplanamayan bileşenler {@code null} olarak döner.</p>
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return temel analiz skor DTO'su, tüm bileşenler null olabilir
     */
    public TemelAnalizSkorDto calculateScores(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) {
            log.warn("[TEMEL-ANALIZ-SKOR] stockCode boş");
            return TemelAnalizSkorDto.builder().build();
        }

        String code = stockCode.toUpperCase().trim();
        TemelAnalizSkorDto.TemelAnalizSkorDtoBuilder builder = TemelAnalizSkorDto.builder()
                .hisseSenediKodu(code);

        // Altman Z-Score
        try {
            Double zScore = calculateAltmanZScore(code);
            if (zScore != null) {
                builder.altmanZScore(Math.round(zScore * 100.0) / 100.0);
                builder.altmanZone(determineAltmanZone(zScore));
            }
        } catch (Exception e) {
            log.warn("[TEMEL-ANALIZ-SKOR] Altman Z-Score hesaplama hatası: stockCode={}", code, e);
        }

        // Piotroski F-Score
        try {
            Integer fScore = calculatePiotroskiFScore(code);
            if (fScore != null) {
                builder.piotroskiFScore(fScore);
                builder.piotroskiZone(determinePiotroskiZone(fScore));
            }
        } catch (Exception e) {
            log.warn("[TEMEL-ANALIZ-SKOR] Piotroski F-Score hesaplama hatası: stockCode={}", code, e);
        }

        // Graham Sayısı
        try {
            Double grahamValue = calculateGrahamNumber(code);
            if (grahamValue != null) {
                builder.grahamSayisi(Math.round(grahamValue * 100.0) / 100.0);

                Double currentPrice = getCurrentPrice(code);
                if (currentPrice != null && currentPrice > 0) {
                    double margin = (grahamValue - currentPrice) / currentPrice * 100.0;
                    builder.grahamMarji(Math.round(margin * 100.0) / 100.0);
                }
            }
        } catch (Exception e) {
            log.warn("[TEMEL-ANALIZ-SKOR] Graham Sayısı hesaplama hatası: stockCode={}", code, e);
        }

        return builder.build();
    }

    /**
     * Altman Z-Score hesaplar.
     *
     * <p>Formül: Z = 1.2*X1 + 1.4*X2 + 3.3*X3 + 0.6*X4 + 1.0*X5</p>
     * <ul>
     *   <li>X1 = İşletme Sermayesi / Toplam Varlıklar</li>
     *   <li>X2 = Dağıtılmamış Kârlar / Toplam Varlıklar</li>
     *   <li>X3 = FVÖK / Toplam Varlıklar</li>
     *   <li>X4 = Piyasa Değeri / Toplam Borçlar</li>
     *   <li>X5 = Satışlar / Toplam Varlıklar</li>
     * </ul>
     *
     * @param stockCode hisse kodu
     * @return Z-Score değeri veya {@code null} (hesaplanamazsa)
     */
    private Double calculateAltmanZScore(String stockCode) {
        if (finansalTabloService == null || finansalOranService == null) {
            return null;
        }

        List<FinansalTabloDto> bilanco = finansalTabloService.getHisseBilanco(stockCode);
        List<FinansalTabloDto> gelir = finansalTabloService.getHisseGelirTablosu(stockCode);
        List<FinansalOranDto> oranlar = finansalOranService.getHisseOranlar(stockCode);

        if (bilanco.isEmpty() || gelir.isEmpty()) {
            log.debug("[ALTMAN] Bilanço veya gelir tablosu bulunamadı: {}", stockCode);
            return null;
        }

        // En son dönemi bul (yıl DESC, ay DESC)
        int[] sonDonem = findLatestPeriod(bilanco);
        if (sonDonem == null) {
            return null;
        }
        int yil = sonDonem[0];
        int ay = sonDonem[1];

        // Bilanço kalemlerini filtrele (son dönem)
        Map<String, Long> bilancoMap = bilanco.stream()
                .filter(d -> d.getYil() != null && d.getYil() == yil && d.getAy() != null && d.getAy() == ay)
                .filter(d -> d.getKalem() != null && d.getTryDonemsel() != null)
                .collect(Collectors.toMap(
                        FinansalTabloDto::getKalem,
                        FinansalTabloDto::getTryDonemsel,
                        (a, b) -> a // İlk değeri koru (duplicate kalem varsa)
                ));

        // Gelir tablosu kalemlerini filtrele (son dönem)
        Map<String, Long> gelirMap = gelir.stream()
                .filter(d -> d.getYil() != null && d.getYil() == yil && d.getAy() != null && d.getAy() == ay)
                .filter(d -> d.getKalem() != null && d.getTryDonemsel() != null)
                .collect(Collectors.toMap(
                        FinansalTabloDto::getKalem,
                        FinansalTabloDto::getTryDonemsel,
                        (a, b) -> a
                ));

        // Toplam Varlıklar
        Long toplamVarliklar = findBilancoValue(bilancoMap, "Toplam Varlıklar", "TOPLAM VARLIKLAR");
        if (toplamVarliklar == null || toplamVarliklar == 0) {
            log.debug("[ALTMAN] Toplam Varlıklar bulunamadı: {}", stockCode);
            return null;
        }

        // X1 = İşletme Sermayesi / Toplam Varlıklar
        Long donenVarliklar = findBilancoValue(bilancoMap, "Toplam Dönen Varlıklar", "Dönen Varlıklar", "DÖNEN VARLIKLAR");
        Long kisaVadeliBorclar = findBilancoValue(bilancoMap, "Kısa Vadeli Borçlar",
                "Toplam Kısa Vadeli Yükümlülükler", "KISA VADELİ YÜKÜMLÜLÜKLER");
        Double x1 = (donenVarliklar != null && kisaVadeliBorclar != null)
                ? (double) (donenVarliklar - kisaVadeliBorclar) / toplamVarliklar : null;

        // X2 = Dağıtılmamış Kârlar / Toplam Varlıklar
        Long gecmisYilKarlari = findBilancoValue(bilancoMap, "Geçmiş Yıllar Karları",
                "Geçmiş Yıllar Kar/Zararları", "GEÇMİŞ YILLAR KARLARI");
        Double x2 = (gecmisYilKarlari != null)
                ? (double) gecmisYilKarlari / toplamVarliklar : null;

        // X3 = FVÖK / Toplam Varlıklar
        Long fvok = findGelirValue(gelirMap, "Esas Faaliyet Karı", "Esas Faaliyet Kar/Zararı",
                "ESAS FAALİYET KARI (ZARARI)");
        Double x3 = (fvok != null)
                ? (double) fvok / toplamVarliklar : null;

        // X4 = Piyasa Değeri / Toplam Borçlar
        // Fintables bilanço kalemleri TRY cinsinden gelir (1000 çarpanı YOK, tam TRY)
        // Fintables finansal oranlardan piyasa değeri de doğrudan gelir
        // Birim dönüşümü gerekmez — her iki değer de aynı birimde
        Double piyasaDegeri = findOranValue(oranlar, yil, ay, "Piyasa Değeri", "Piyasa Değeri (mn TL)");
        Long toplamBorclar = findBilancoValue(bilancoMap, "Toplam Yükümlülükler",
                "TOPLAM YÜKÜMLÜLÜKLER", "Toplam Borçlar");
        Double x4 = null;
        if (piyasaDegeri != null && toplamBorclar != null && toplamBorclar != 0) {
            x4 = piyasaDegeri / toplamBorclar.doubleValue();
        }

        // X5 = Satışlar / Toplam Varlıklar
        Long hasilat = findGelirValue(gelirMap, "Hasılat", "HASILAT", "Satış Gelirleri");
        Double x5 = (hasilat != null)
                ? (double) hasilat / toplamVarliklar : null;

        // Altman Z-Score formülü 5 bileşenin TAMAMINI gerektirir — eksik bileşenle partial hesap hatalı sonuç verir
        if (x1 == null || x2 == null || x3 == null || x4 == null || x5 == null) {
            log.debug("[ALTMAN] Eksik bileşen, Z-Score hesaplanamadı: {} (x1={}, x2={}, x3={}, x4={}, x5={})",
                    stockCode, x1, x2, x3, x4, x5);
            return null;
        }

        // Z = 1.2*X1 + 1.4*X2 + 3.3*X3 + 0.6*X4 + 1.0*X5
        double z = 1.2 * x1 + 1.4 * x2 + 3.3 * x3 + 0.6 * x4 + 1.0 * x5;

        log.debug("[ALTMAN] {} Z={} (X1={}, X2={}, X3={}, X4={}, X5={})",
                stockCode, String.format("%.2f", z), x1, x2, x3, x4, x5);
        return z;
    }

    /**
     * Piotroski F-Score hesaplar (9 kriter, her biri 0 veya 1).
     *
     * <p>Kârlılık (4 puan), Kaldıraç (3 puan), Operasyonel (2 puan)
     * olmak üzere toplam 9 kriter üzerinden değerlendirilir.
     * Son 2 dönem karşılaştırması gerektirir.</p>
     *
     * @param stockCode hisse kodu
     * @return F-Score (0-9) veya {@code null} (hesaplanamazsa)
     */
    private Integer calculatePiotroskiFScore(String stockCode) {
        if (finansalTabloService == null || finansalOranService == null) {
            return null;
        }

        List<FinansalTabloDto> bilanco = finansalTabloService.getHisseBilanco(stockCode);
        List<FinansalTabloDto> gelir = finansalTabloService.getHisseGelirTablosu(stockCode);
        List<FinansalTabloDto> nakitAkim = finansalTabloService.getHisseNakitAkim(stockCode);
        List<FinansalOranDto> oranlar = finansalOranService.getHisseOranlar(stockCode);

        if (bilanco.isEmpty() || gelir.isEmpty()) {
            return null;
        }

        // Son 2 dönemi bul
        int[] sonDonem = findLatestPeriod(bilanco);
        if (sonDonem == null) return null;
        int[] oncekiDonem = findPreviousPeriod(bilanco, sonDonem[0], sonDonem[1]);
        if (oncekiDonem == null) {
            log.debug("[PIOTROSKI] Önceki dönem bulunamadı: {}", stockCode);
            return null;
        }

        int yil = sonDonem[0], ay = sonDonem[1];
        int oncYil = oncekiDonem[0], oncAy = oncekiDonem[1];

        // Son dönem kalemleri
        Map<String, Long> bilancoSon = periodMap(bilanco, yil, ay);
        Map<String, Long> gelirSon = periodMap(gelir, yil, ay);
        Map<String, Long> nakitSon = periodMap(nakitAkim, yil, ay);

        // Önceki dönem kalemleri
        Map<String, Long> bilancoOnc = periodMap(bilanco, oncYil, oncAy);
        Map<String, Long> gelirOnc = periodMap(gelir, oncYil, oncAy);

        int score = 0;

        // === KÂRLILIK (4 puan) ===

        // 1. Net Kâr > 0
        Long netKar = findGelirValue(gelirSon, "Net Dönem Karı", "Dönem Net Karı",
                "NET DÖNEM KARI (ZARARI)", "Ana Ortaklık Payları");
        if (netKar != null && netKar > 0) score++;

        // 2. Faaliyetten Nakit Akışı > 0
        Long faaliyetNakit = findGelirValue(nakitSon, "İşletme Faaliyetlerinden Nakit Akışları",
                "İŞLETME FAALİYETLERİNDEN NAKİT AKIŞLARI", "Esas Faaliyet Nakit Akımı");
        if (faaliyetNakit != null && faaliyetNakit > 0) score++;

        // 3. ROA artışı (son dönem > önceki dönem)
        Double roaSon = findOranValue(oranlar, yil, ay, "ROA", "Aktif Karlılık");
        Double roaOnc = findOranValue(oranlar, oncYil, oncAy, "ROA", "Aktif Karlılık");
        if (roaSon != null && roaOnc != null && roaSon > roaOnc) score++;

        // 4. Faaliyetten Nakit > Net Kâr (tahakkuk kalitesi)
        if (faaliyetNakit != null && netKar != null && faaliyetNakit > netKar) score++;

        // === KALDIRAÇ (3 puan) ===

        // 5. Uzun vadeli borç azalışı
        Long uzunVadeSon = findBilancoValue(bilancoSon, "Uzun Vadeli Borçlar",
                "Toplam Uzun Vadeli Yükümlülükler", "UZUN VADELİ YÜKÜMLÜLÜKLER");
        Long uzunVadeOnc = findBilancoValue(bilancoOnc, "Uzun Vadeli Borçlar",
                "Toplam Uzun Vadeli Yükümlülükler", "UZUN VADELİ YÜKÜMLÜLÜKLER");
        if (uzunVadeSon != null && uzunVadeOnc != null && uzunVadeSon <= uzunVadeOnc) score++;

        // 6. Cari oran artışı
        Double cariOranSon = findOranValue(oranlar, yil, ay, "Cari Oran");
        Double cariOranOnc = findOranValue(oranlar, oncYil, oncAy, "Cari Oran");
        if (cariOranSon != null && cariOranOnc != null && cariOranSon > cariOranOnc) score++;

        // 7. Yeni hisse ihracı yok (özkaynak içindeki sermaye değişimi)
        Long sermayeSon = findBilancoValue(bilancoSon, "Ödenmiş Sermaye", "ÖDENMİŞ SERMAYE");
        Long sermayeOnc = findBilancoValue(bilancoOnc, "Ödenmiş Sermaye", "ÖDENMİŞ SERMAYE");
        if (sermayeSon != null && sermayeOnc != null && sermayeSon <= sermayeOnc) score++;

        // === OPERASYONEL (2 puan) ===

        // 8. Brüt kâr marjı artışı
        Double brutMarjSon = findOranValue(oranlar, yil, ay, "Brüt Kar Marjı", "Brüt Kâr Marjı");
        Double brutMarjOnc = findOranValue(oranlar, oncYil, oncAy, "Brüt Kar Marjı", "Brüt Kâr Marjı");
        if (brutMarjSon != null && brutMarjOnc != null && brutMarjSon > brutMarjOnc) score++;

        // 9. Varlık devir hızı artışı
        Double devHizSon = findOranValue(oranlar, yil, ay, "Aktif Devir Hızı", "Varlık Devir Hızı");
        Double devHizOnc = findOranValue(oranlar, oncYil, oncAy, "Aktif Devir Hızı", "Varlık Devir Hızı");
        if (devHizSon != null && devHizOnc != null && devHizSon > devHizOnc) score++;

        log.debug("[PIOTROSKI] {} F-Score={}/9 (dönem: {}/{} vs {}/{})",
                stockCode, score, yil, ay, oncYil, oncAy);
        return score;
    }

    /**
     * Graham Sayısı (içsel değer) hesaplar.
     *
     * <p>Formül: {@code Graham = sqrt(22.5 * EPS * BV)}</p>
     * <ul>
     *   <li>EPS = Hisse Başına Kazanç (FinansalOranService'ten)</li>
     *   <li>BV = Defter Değeri / Hisse Başına Defter Değeri (FinansalOranService'ten)</li>
     * </ul>
     *
     * @param stockCode hisse kodu
     * @return Graham değeri veya {@code null} (hesaplanamazsa)
     */
    private Double calculateGrahamNumber(String stockCode) {
        if (finansalOranService == null) {
            return null;
        }

        List<FinansalOranDto> oranlar = finansalOranService.getHisseOranlar(stockCode);
        if (oranlar.isEmpty()) {
            return null;
        }

        // En son dönemi bul
        int[] sonDonem = findLatestOranPeriod(oranlar);
        if (sonDonem == null) return null;

        Double eps = findOranValue(oranlar, sonDonem[0], sonDonem[1],
                "Hisse Başına Kazanç", "HBK", "EPS");
        Double bv = findOranValue(oranlar, sonDonem[0], sonDonem[1],
                "Defter Değeri", "Hisse Başına Defter Değeri", "PD/DD");

        // PD/DD oran olarak gelir — defter değeri hesaplaması farklı
        // Önce doğrudan defter değeri ara
        if (bv == null) {
            // PD/DD'den defter değeri hesapla: BV = Fiyat / (PD/DD)
            Double pddd = findOranValue(oranlar, sonDonem[0], sonDonem[1], "PD/DD");
            Double currentPrice = getCurrentPrice(stockCode);
            if (pddd != null && pddd > 0 && currentPrice != null && currentPrice > 0) {
                bv = currentPrice / pddd;
            }
        }

        if (eps == null || bv == null) {
            log.debug("[GRAHAM] EPS veya BV bulunamadı: {} (eps={}, bv={})", stockCode, eps, bv);
            return null;
        }

        // EPS ve BV negatifse Graham hesaplanamaz
        if (eps <= 0 || bv <= 0) {
            log.debug("[GRAHAM] EPS veya BV negatif: {} (eps={}, bv={})", stockCode, eps, bv);
            return null;
        }

        double graham = Math.sqrt(GRAHAM_MULTIPLIER * eps * bv);
        log.debug("[GRAHAM] {} Graham={} (EPS={}, BV={})", stockCode, String.format("%.2f", graham), eps, bv);
        return graham;
    }

    // ==================== Yardımcı Metodlar ====================

    /**
     * Altman Z-Score bölgesini belirler.
     *
     * @param zScore Z-Score değeri
     * @return "GUVENLI", "GRI" veya "TEHLIKE"
     */
    private String determineAltmanZone(double zScore) {
        if (zScore > ALTMAN_SAFE_THRESHOLD) return "GUVENLI";
        if (zScore < ALTMAN_DISTRESS_THRESHOLD) return "TEHLIKE";
        return "GRI";
    }

    /**
     * Piotroski F-Score bölgesini belirler.
     *
     * @param fScore F-Score değeri (0-9)
     * @return "GUCLU", "ORTA" veya "ZAYIF"
     */
    private String determinePiotroskiZone(int fScore) {
        if (fScore >= PIOTROSKI_STRONG_THRESHOLD) return "GUCLU";
        if (fScore <= PIOTROSKI_WEAK_THRESHOLD) return "ZAYIF";
        return "ORTA";
    }

    /**
     * Finansal tablo listesinden en son dönemi (yıl, ay) bulur.
     *
     * @param tablolar finansal tablo listesi
     * @return {@code [yıl, ay]} dizisi veya {@code null}
     */
    private int[] findLatestPeriod(List<FinansalTabloDto> tablolar) {
        return tablolar.stream()
                .filter(d -> d.getYil() != null && d.getAy() != null)
                .max(Comparator.comparingInt((FinansalTabloDto d) -> d.getYil() * 100 + d.getAy()))
                .map(d -> new int[]{d.getYil(), d.getAy()})
                .orElse(null);
    }

    /**
     * Belirtilen dönemden bir önceki dönemi bulur.
     *
     * @param tablolar finansal tablo listesi
     * @param yil      mevcut yıl
     * @param ay       mevcut ay
     * @return {@code [yıl, ay]} dizisi veya {@code null}
     */
    private int[] findPreviousPeriod(List<FinansalTabloDto> tablolar, int yil, int ay) {
        return tablolar.stream()
                .filter(d -> d.getYil() != null && d.getAy() != null)
                .filter(d -> (d.getYil() * 100 + d.getAy()) < (yil * 100 + ay))
                .max(Comparator.comparingInt((FinansalTabloDto d) -> d.getYil() * 100 + d.getAy()))
                .map(d -> new int[]{d.getYil(), d.getAy()})
                .orElse(null);
    }

    /**
     * Finansal oran listesinden en son dönemi (yıl, ay) bulur.
     *
     * @param oranlar finansal oran listesi
     * @return {@code [yıl, ay]} dizisi veya {@code null}
     */
    private int[] findLatestOranPeriod(List<FinansalOranDto> oranlar) {
        return oranlar.stream()
                .filter(d -> d.getYil() != null && d.getAy() != null)
                .max(Comparator.comparingInt((FinansalOranDto d) -> d.getYil() * 100 + d.getAy()))
                .map(d -> new int[]{d.getYil(), d.getAy()})
                .orElse(null);
    }

    /**
     * Belirtilen dönemin bilanço/gelir kalemlerini map olarak döndürür.
     *
     * @param tablolar finansal tablo listesi
     * @param yil      dönem yılı
     * @param ay       dönem ayı
     * @return kalem adı → TRY dönemsel değer map'i
     */
    private Map<String, Long> periodMap(List<FinansalTabloDto> tablolar, int yil, int ay) {
        return tablolar.stream()
                .filter(d -> d.getYil() != null && d.getYil() == yil && d.getAy() != null && d.getAy() == ay)
                .filter(d -> d.getKalem() != null && d.getTryDonemsel() != null)
                .collect(Collectors.toMap(
                        FinansalTabloDto::getKalem,
                        FinansalTabloDto::getTryDonemsel,
                        (a, b) -> a
                ));
    }

    /**
     * Bilanço map'inden çoklu kalem adı ile değer arar.
     *
     * @param map       kalem adı → değer map'i
     * @param kalemler  aranacak kalem adları (öncelik sırasıyla)
     * @return ilk bulunan değer veya {@code null}
     */
    private Long findBilancoValue(Map<String, Long> map, String... kalemler) {
        for (String kalem : kalemler) {
            Long val = map.get(kalem);
            if (val != null) return val;
        }
        // Kısmi eşleşme dene (Fintables kalem adları değişkenlik gösterebilir)
        for (String kalem : kalemler) {
            String lowerKalem = kalem.toLowerCase();
            for (Map.Entry<String, Long> entry : map.entrySet()) {
                if (entry.getKey().toLowerCase().contains(lowerKalem)
                        || lowerKalem.contains(entry.getKey().toLowerCase())) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    /**
     * Gelir tablosu map'inden çoklu kalem adı ile değer arar.
     *
     * @param map       kalem adı → değer map'i
     * @param kalemler  aranacak kalem adları (öncelik sırasıyla)
     * @return ilk bulunan değer veya {@code null}
     */
    private Long findGelirValue(Map<String, Long> map, String... kalemler) {
        return findBilancoValue(map, kalemler); // Aynı arama mantığı
    }

    /**
     * Finansal oran listesinden belirtilen dönem ve oran adları ile değer arar.
     *
     * @param oranlar    finansal oran listesi
     * @param yil        dönem yılı
     * @param ay         dönem ayı
     * @param oranAdlari aranacak oran adları (öncelik sırasıyla)
     * @return ilk bulunan oran değeri veya {@code null}
     */
    private Double findOranValue(List<FinansalOranDto> oranlar, int yil, int ay, String... oranAdlari) {
        for (String oranAdi : oranAdlari) {
            Optional<FinansalOranDto> found = oranlar.stream()
                    .filter(o -> o.getYil() != null && o.getYil() == yil
                            && o.getAy() != null && o.getAy() == ay
                            && o.getOran() != null && o.getDeger() != null)
                    .filter(o -> o.getOran().equalsIgnoreCase(oranAdi)
                            || o.getOran().toLowerCase().contains(oranAdi.toLowerCase()))
                    .findFirst();
            if (found.isPresent()) {
                return found.get().getDeger();
            }
        }
        return null;
    }

    /**
     * QuotePriceCache'den hissenin güncel fiyatını alır.
     *
     * @param stockCode hisse kodu
     * @return güncel fiyat veya {@code null}
     */
    private Double getCurrentPrice(String stockCode) {
        if (quotePriceCache == null) {
            return null;
        }
        try {
            Map<String, Object> quote = quotePriceCache.get("BIST:" + stockCode);
            if (quote == null) {
                quote = quotePriceCache.get(stockCode);
            }
            if (quote != null) {
                Object price = quote.get("lp"); // last price
                if (price == null) price = quote.get("close");
                if (price instanceof Number) {
                    return ((Number) price).doubleValue();
                }
            }
        } catch (Exception e) {
            log.debug("[TEMEL-ANALIZ-SKOR] Fiyat alma hatası: {}", stockCode, e);
        }
        return null;
    }
}
