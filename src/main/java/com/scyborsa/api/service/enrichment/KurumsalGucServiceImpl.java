package com.scyborsa.api.service.enrichment;

import com.scyborsa.api.dto.enrichment.FonPozisyon;
import com.scyborsa.api.dto.enrichment.KurumsalGucDTO;
import com.scyborsa.api.dto.enrichment.StockBrokerInfo;
import com.scyborsa.api.dto.fintables.AcigaSatisDto;
import com.scyborsa.api.dto.fintables.FinansalOranDto;
import com.scyborsa.api.dto.fintables.HedefFiyatDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Kurumsal Guc Skoru (KGS) hesaplama servisi implementasyonu.
 *
 * <p>5 bileşenden oluşan 0-100 arasında bir skor hesaplar:</p>
 * <ul>
 *   <li><b>Finansal Oranlar (30 puan):</b> ROE, F/K, PD/DD</li>
 *   <li><b>Hedef Fiyat Konsensüs (20 puan):</b> Analist tavsiye sayısı ve yön dağılımı</li>
 *   <li><b>Fon Pozisyonları (20 puan):</b> Fon sayısı ve toplam lot</li>
 *   <li><b>VBTS Durumu (10 puan):</b> Tedbirli hisse cezasi</li>
 *   <li><b>Aciga Satis (10 puan):</b> Aciga satis orani</li>
 * </ul>
 *
 * <p><b>Graceful degradation:</b> Tum veri kaynaklari opsiyoneldir.
 * Herhangi biri basarisiz olursa o bilesen 0 puan alir ve
 * {@code confidenceScore} duser (ADR-017).</p>
 *
 * @see KurumsalGucService
 * @see KurumsalGucDTO
 */
@Slf4j
@Service
public class KurumsalGucServiceImpl implements KurumsalGucService {

    /** Finansal oran servisi (opsiyonel — yoksa finansal bilesen 0 puan). */
    @Autowired(required = false)
    private FinansalOranService finansalOranService;

    /** Hedef fiyat servisi (opsiyonel — yoksa hedef fiyat bileşeni 0 puan). */
    @Autowired(required = false)
    private HedefFiyatService hedefFiyatService;

    /** Fon pozisyon servisi (opsiyonel — yoksa fon bileşeni 0 puan). */
    @Autowired(required = false)
    private FintablesFonPozisyonService fintablesFonPozisyonService;

    /** VBTS tedbir servisi (opsiyonel — yoksa tedbir bileşeni 0 puan). */
    @Autowired(required = false)
    private VbtsTedbirService vbtsTedbirService;

    /** Aciga satis servisi (opsiyonel — yoksa aciga satis bileşeni 0 puan). */
    @Autowired(required = false)
    private AcigaSatisService acigaSatisService;

    /** AKD kurum dagitim servisi (opsiyonel — momentum skoru icin). */
    @Autowired(required = false)
    private PerStockAKDService perStockAKDService;

    /**
     * Verilen hisse icin Kurumsal Guc Skoru (KGS) hesaplar.
     *
     * <p>Her bilesen bagimsiz olarak hesaplanir. Bir bilesen basarisiz olursa
     * 0 puan alir, toplam skor mevcut bilesenler uzerinden hesaplanir.
     * {@code confidenceScore} basarili bilesen sayisina gore belirlenir.</p>
     *
     * @param stockName hisse kodu (orn. "THYAO")
     * @return KGS sonucu (hicbir veri yoksa skor=null, confidenceScore=0)
     */
    @Override
    public KurumsalGucDTO calculateScore(String stockName) {
        if (stockName == null || stockName.isBlank()) {
            return buildEmptyResult();
        }

        String normalizedCode = stockName.trim().toUpperCase();
        log.debug("[KGS] Skor hesaplaniyor: {}", normalizedCode);

        int totalScore = 0;
        int availableComponents = 0;
        int totalComponents = 5;

        // 1. Finansal Oranlar (30 puan)
        int finansalPuan = calculateFinansalPuan(normalizedCode);
        if (finansalPuan >= 0) {
            totalScore += finansalPuan;
            availableComponents++;
        }

        // 2. Hedef Fiyat Konsensus (20 puan)
        int hedefFiyatPuan = calculateHedefFiyatPuan(normalizedCode);
        if (hedefFiyatPuan >= 0) {
            totalScore += hedefFiyatPuan;
            availableComponents++;
        }

        // 3. Fon Pozisyonlari (20 puan)
        int fonPuan = calculateFonPuan(normalizedCode);
        if (fonPuan >= 0) {
            totalScore += fonPuan;
            availableComponents++;
        }

        // 4. VBTS Durumu (10 puan)
        int vbtsPuan = calculateVbtsPuan(normalizedCode);
        if (vbtsPuan >= -10) { // -10 da gecerli bilesen
            totalScore += vbtsPuan;
            availableComponents++;
        }

        // 5. Aciga Satis (10 puan)
        int acigaSatisPuan = calculateAcigaSatisPuan(normalizedCode);
        if (acigaSatisPuan >= 0) {
            totalScore += acigaSatisPuan;
            availableComponents++;
        }

        // Hicbir bilesen yoksa bos sonuc don
        if (availableComponents == 0) {
            log.debug("[KGS] Hicbir bilesen mevcut degil: {}", normalizedCode);
            return buildEmptyResult();
        }

        // Skoru 0-100 arasina normalize et
        int normalizedScore = Math.max(0, Math.min(100, totalScore));

        // Guven skoru: basarili bilesen orani
        int confidenceScore = (availableComponents * 100) / totalComponents;

        // Momentum skoru (AKD bazli)
        Integer momentumSkoru = calculateMomentumSkoru(normalizedCode);

        // AKD bazli ek alanlar
        String formattedNetPozisyonTL = null;
        String formattedYogunlasma = null;
        Integer aliciKurumSayisi = null;

        if (perStockAKDService != null) {
            try {
                List<StockBrokerInfo> brokers = perStockAKDService.getStockBrokerDistribution(normalizedCode);
                if (brokers != null && !brokers.isEmpty()) {
                    long aliciSayisi = brokers.stream()
                            .filter(b -> b.getFormattedVolume() != null && !b.getFormattedVolume().startsWith("-"))
                            .count();
                    aliciKurumSayisi = (int) aliciSayisi;
                }
            } catch (Exception e) {
                log.debug("[KGS] AKD ek alanlar alinamadi ({}): {}", normalizedCode, e.getMessage());
            }
        }

        // Emoji ve etiket mapping
        String emoji = getEmoji(normalizedScore);
        String etiket = getEtiket(normalizedScore);

        log.info("[KGS] {} | Skor: {} | Guven: {}% | Bilesenler: {}/{} | Etiket: {}",
                normalizedCode, normalizedScore, confidenceScore, availableComponents, totalComponents, etiket);

        return KurumsalGucDTO.builder()
                .skor(normalizedScore)
                .emoji(emoji)
                .etiket(etiket)
                .momentumSkoru(momentumSkoru)
                .formattedNetPozisyonTL(formattedNetPozisyonTL)
                .formattedSureklilik(null)
                .formattedYogunlasma(formattedYogunlasma)
                .aliciKurumSayisi(aliciKurumSayisi)
                .confidenceScore(confidenceScore)
                .build();
    }

    // ==================== BILESEN HESAPLAMALARI ====================

    /**
     * Finansal oranlar bileşeni hesaplar (maks 30 puan).
     *
     * <p>ROE, F/K, PD/DD degerlerine gore puan verir.
     * Yuksek ROE, dusuk F/K ve makul PD/DD olumlu puanlanir.</p>
     *
     * @param stockCode hisse kodu
     * @return 0-30 arasi puan, servis yoksa -1
     */
    private int calculateFinansalPuan(String stockCode) {
        if (finansalOranService == null) {
            return -1;
        }

        try {
            List<FinansalOranDto> oranlar = finansalOranService.getHisseOranlar(stockCode);
            if (oranlar == null || oranlar.isEmpty()) {
                return -1;
            }

            // Son donemi bul
            Integer maxYil = oranlar.stream()
                    .map(FinansalOranDto::getYil)
                    .filter(y -> y != null)
                    .max(Integer::compareTo)
                    .orElse(null);
            if (maxYil == null) return -1;

            Integer maxAy = oranlar.stream()
                    .filter(o -> maxYil.equals(o.getYil()))
                    .map(FinansalOranDto::getAy)
                    .filter(a -> a != null)
                    .max(Integer::compareTo)
                    .orElse(null);
            if (maxAy == null) return -1;

            // Son donem oranlari
            List<FinansalOranDto> sonDonem = oranlar.stream()
                    .filter(o -> maxYil.equals(o.getYil()) && maxAy.equals(o.getAy()))
                    .toList();

            int puan = 0;

            // ROE (maks 15 puan)
            Double roe = findOranDeger(sonDonem, "ROE");
            if (roe != null) {
                if (roe > 25) puan += 15;
                else if (roe > 15) puan += 12;
                else if (roe > 10) puan += 8;
                else if (roe > 5) puan += 4;
                else if (roe > 0) puan += 2;
                // Negatif ROE = 0 puan
            }

            // F/K — Fiyat/Kazanc (maks 15 puan, dusuk = iyi)
            Double fk = findOranDeger(sonDonem, "F/K");
            if (fk != null && fk > 0) {
                if (fk < 5) puan += 15;
                else if (fk < 10) puan += 12;
                else if (fk < 15) puan += 8;
                else if (fk < 25) puan += 5;
                else if (fk < 40) puan += 2;
                // F/K > 40 = 0 puan
            }

            // PD/DD — Piyasa Degeri / Defter Degeri (maks 10 puan, dusuk = iyi)
            Double pddd = findOranDeger(sonDonem, "PD/DD");
            if (pddd != null && pddd > 0) {
                if (pddd < 1) puan += 10;
                else if (pddd < 2) puan += 8;
                else if (pddd < 3) puan += 5;
                else if (pddd < 5) puan += 2;
                // PD/DD > 5 = 0 puan
            }

            return Math.min(30, puan);

        } catch (Exception e) {
            log.debug("[KGS] Finansal oranlar hesaplanamadi ({}): {}", stockCode, e.getMessage());
            return -1;
        }
    }

    /**
     * Hedef fiyat konsensüs bileşeni hesaplar (maks 20 puan).
     *
     * <p>Tavsiye sayisi ve tavsiye yon dagilimine gore puan verir.
     * Fazla "AL" tavsiyesi olumlu puanlanir.</p>
     *
     * @param stockCode hisse kodu
     * @return 0-20 arasi puan, servis yoksa -1
     */
    private int calculateHedefFiyatPuan(String stockCode) {
        if (hedefFiyatService == null) {
            return -1;
        }

        try {
            List<HedefFiyatDto> hedefler = hedefFiyatService.getHisseHedefFiyatlar(stockCode);
            if (hedefler == null || hedefler.isEmpty()) {
                return -1;
            }

            int puan = 0;

            // Tavsiye sayisi (maks 10 puan)
            int tavsiyeSayisi = hedefler.size();
            if (tavsiyeSayisi >= 10) puan += 10;
            else if (tavsiyeSayisi >= 5) puan += 7;
            else if (tavsiyeSayisi >= 3) puan += 5;
            else if (tavsiyeSayisi >= 1) puan += 2;

            // Tavsiye yonu (maks 10 puan)
            long alSayisi = hedefler.stream()
                    .filter(h -> h.getTavsiye() != null
                            && (h.getTavsiye().toUpperCase().contains("AL")
                                || h.getTavsiye().toUpperCase().contains("OUTPERFORM")
                                || h.getTavsiye().toUpperCase().contains("BUY")))
                    .count();

            double alOrani = tavsiyeSayisi > 0 ? (double) alSayisi / tavsiyeSayisi : 0;
            if (alOrani > 0.7) puan += 10;
            else if (alOrani > 0.5) puan += 7;
            else if (alOrani > 0.3) puan += 4;
            else if (alOrani > 0) puan += 2;

            return Math.min(20, puan);

        } catch (Exception e) {
            log.debug("[KGS] Hedef fiyat hesaplanamadi ({}): {}", stockCode, e.getMessage());
            return -1;
        }
    }

    /**
     * Fon Pozisyonları bileşeni hesaplar (maks 20 puan).
     *
     * <p>Hisseyi tutan fon sayisi ve toplam lot miktarina gore puan verir.</p>
     *
     * @param stockCode hisse kodu
     * @return 0-20 arasi puan, servis yoksa -1
     */
    private int calculateFonPuan(String stockCode) {
        if (fintablesFonPozisyonService == null) {
            return -1;
        }

        try {
            List<FonPozisyon> pozisyonlar = fintablesFonPozisyonService.getFonPozisyonlari(stockCode);
            if (pozisyonlar == null || pozisyonlar.isEmpty()) {
                return -1;
            }

            int puan = 0;

            // Fon sayisi (maks 10 puan)
            int fonSayisi = pozisyonlar.size();
            if (fonSayisi >= 20) puan += 10;
            else if (fonSayisi >= 10) puan += 8;
            else if (fonSayisi >= 5) puan += 5;
            else if (fonSayisi >= 2) puan += 3;
            else puan += 1;

            // Toplam lot (maks 10 puan)
            long toplamLot = pozisyonlar.stream()
                    .mapToLong(FonPozisyon::getNominal)
                    .sum();

            if (toplamLot >= 10_000_000) puan += 10;
            else if (toplamLot >= 5_000_000) puan += 8;
            else if (toplamLot >= 1_000_000) puan += 5;
            else if (toplamLot >= 100_000) puan += 3;
            else if (toplamLot > 0) puan += 1;

            return Math.min(20, puan);

        } catch (Exception e) {
            log.debug("[KGS] Fon pozisyonlari hesaplanamadi ({}): {}", stockCode, e.getMessage());
            return -1;
        }
    }

    /**
     * VBTS tedbir durumu bileşeni hesaplar (maks 10, min -10 puan).
     *
     * <p>Tedbirli hisseler -10 puan cezasi alir, tedbirsiz hisseler +10 puan alir.</p>
     *
     * @param stockCode hisse kodu
     * @return -10 ile +10 arasi puan, servis yoksa Integer.MIN_VALUE
     */
    private int calculateVbtsPuan(String stockCode) {
        if (vbtsTedbirService == null) {
            return Integer.MIN_VALUE;
        }

        try {
            boolean tedbirli = vbtsTedbirService.isTedbirli(stockCode);
            return tedbirli ? -10 : 10;

        } catch (Exception e) {
            log.debug("[KGS] VBTS kontrolu yapilamadi ({}): {}", stockCode, e.getMessage());
            return Integer.MIN_VALUE;
        }
    }

    /**
     * Aciga satis bileşeni hesaplar (maks 10 puan).
     *
     * <p>Dusuk aciga satis orani olumlu puanlanir. Yuksek oran olumsuz sinyal.</p>
     *
     * @param stockCode hisse kodu
     * @return 0-10 arasi puan, servis yoksa -1
     */
    private int calculateAcigaSatisPuan(String stockCode) {
        if (acigaSatisService == null) {
            return -1;
        }

        try {
            List<AcigaSatisDto> acigaSatislar = acigaSatisService.getHisseAcigaSatis(stockCode);
            if (acigaSatislar == null || acigaSatislar.isEmpty()) {
                // Aciga satis verisi yok = olumlu (aciga satis yapilmiyor)
                return 10;
            }

            // Tarihe gore en son kaydi al
            AcigaSatisDto son = acigaSatislar.stream()
                    .filter(a -> a.getTarih() != null)
                    .max(Comparator.comparing(AcigaSatisDto::getTarih))
                    .orElse(acigaSatislar.get(0));

            // Aciga satis orani hesapla: acigaSatisHacmiTl / toplamIslemHacmiTl
            Double acigaHacim = son.getAcigaSatisHacmiTl();
            Double toplamHacim = son.getToplamIslemHacmiTl();

            if (acigaHacim == null || toplamHacim == null || toplamHacim <= 0) {
                return 10; // Veri yoksa tam puan
            }

            double oran = acigaHacim / toplamHacim;

            if (oran < 0.01) return 10;       // %1'den az — cok iyi
            else if (oran < 0.05) return 8;   // %5'ten az — iyi
            else if (oran < 0.10) return 5;   // %10'dan az — orta
            else if (oran < 0.20) return 2;   // %20'den az — zayif
            else return 0;                     // %20'den fazla — kotu

        } catch (Exception e) {
            log.debug("[KGS] Aciga satis hesaplanamadi ({}): {}", stockCode, e.getMessage());
            return -1;
        }
    }

    /**
     * Momentum skorunu hesaplar (AKD kurum dagitimi bazli).
     *
     * <p>Alici kurum agirlikli dagitim, momentum sinyali olarak yorumlanir.</p>
     *
     * @param stockCode hisse kodu
     * @return 0-100 arasi momentum skoru veya null (veri yoksa)
     */
    private Integer calculateMomentumSkoru(String stockCode) {
        if (perStockAKDService == null) {
            return null;
        }

        try {
            List<StockBrokerInfo> brokers = perStockAKDService.getStockBrokerDistribution(stockCode);
            if (brokers == null || brokers.isEmpty()) {
                return null;
            }

            long aliciSayisi = brokers.stream()
                    .filter(b -> b.getFormattedVolume() != null && !b.getFormattedVolume().startsWith("-"))
                    .count();

            long saticiSayisi = brokers.stream()
                    .filter(b -> b.getFormattedVolume() != null && b.getFormattedVolume().startsWith("-"))
                    .count();

            long toplam = aliciSayisi + saticiSayisi;
            if (toplam == 0) return 50; // Notr

            return (int) ((aliciSayisi * 100) / toplam);

        } catch (Exception e) {
            log.debug("[KGS] Momentum skoru hesaplanamadi ({}): {}", stockCode, e.getMessage());
            return null;
        }
    }

    // ==================== YARDIMCI METODLAR ====================

    /**
     * Finansal oran listesinden belirtilen oran adinin degerini bulur.
     *
     * @param oranlar oran listesi
     * @param oranAdi aranan oran adi (orn: "ROE", "F/K")
     * @return oran degeri veya null
     */
    private Double findOranDeger(List<FinansalOranDto> oranlar, String oranAdi) {
        return oranlar.stream()
                .filter(o -> oranAdi.equalsIgnoreCase(o.getOran()) && o.getDeger() != null)
                .map(FinansalOranDto::getDeger)
                .findFirst()
                .orElse(null);
    }

    /**
     * Skora gore emoji dondurur.
     *
     * @param skor KGS skoru (0-100)
     * @return skor emojisi
     */
    private String getEmoji(int skor) {
        if (skor >= 80) return "\uD83D\uDFE2\uD83D\uDFE2"; // green green
        if (skor >= 60) return "\uD83D\uDFE2";               // green
        if (skor >= 40) return "\u26AA";                      // white
        if (skor >= 20) return "\uD83D\uDFE1";               // yellow
        return "\uD83D\uDD34";                                // red
    }

    /**
     * Skora gore Turkce etiket dondurur.
     *
     * @param skor KGS skoru (0-100)
     * @return skor etiketi
     */
    private String getEtiket(int skor) {
        if (skor >= 80) return "Guclu Birikim";
        if (skor >= 60) return "Birikim";
        if (skor >= 40) return "Notr";
        if (skor >= 20) return "Zayif";
        return "Dagitim";
    }

    /**
     * Bos KGS sonucu olusturur (hicbir veri mevcut degilken).
     *
     * @return skor=null, confidenceScore=0 olan bos DTO
     */
    private KurumsalGucDTO buildEmptyResult() {
        return KurumsalGucDTO.builder()
                .skor(null)
                .emoji(null)
                .etiket(null)
                .confidenceScore(0)
                .build();
    }
}
