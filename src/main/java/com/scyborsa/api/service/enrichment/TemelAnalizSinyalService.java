package com.scyborsa.api.service.enrichment;

import com.scyborsa.api.dto.fintables.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Temel analiz sinyal servisi.
 *
 * <p>Bir hissenin temel analiz verilerinden (F/K, ROE, borç oranları,
 * Piotroski F-Score, Altman Z-Score, VBTS, açığa satış) otomatik
 * sinyaller üretir.</p>
 *
 * <p>Her sinyal kuralı bağımsız try-catch ile sarılıdır; bir kural
 * başarısız olursa diğerleri yine çalışır (graceful degradation).</p>
 *
 * <h3>Sinyal Kuralları:</h3>
 * <ol>
 *   <li>Düşük F/K — F/K &lt; sektör medyanı * 0.7</li>
 *   <li>Güçlü ROE — ROE &gt; %15 ve artan</li>
 *   <li>Borç Azaltma — Net Borç/FAVÖK azalan</li>
 *   <li>Değer Fırsatı — PD/DD &lt; 1 ve net kâr &gt; 0</li>
 *   <li>Güçlü Temel — Piotroski F-Score &ge; 7</li>
 *   <li>İflas Riski — Altman Z-Score &lt; 1.81</li>
 *   <li>VBTS Tedbiri — VBTS tedbirli hisse</li>
 *   <li>Yüksek Açığa Satış — Açığa satış oranı yüksek</li>
 * </ol>
 *
 * @see TemelAnalizSinyalDto
 */
@Slf4j
@Service
public class TemelAnalizSinyalService {

    /** Istanbul saat dilimi. */
    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");

    /** Düşük F/K eşik çarpanı (sektör medyanının %70'i). */
    private static final double FK_UCUZ_CARPAN = 0.7;

    /** Güçlü ROE alt sınırı (%). */
    private static final double ROE_GUCLU_ESIK = 15.0;

    /** Piotroski güçlü alt sınırı. */
    private static final int PIOTROSKI_GUCLU_ESIK = 7;

    /** Altman tehlike üst sınırı. */
    private static final double ALTMAN_TEHLIKE_ESIK = 1.81;

    /** Yüksek açığa satış oranı eşiği (%). */
    private static final double ACIGA_SATIS_YUKSEK_ESIK = 5.0;

    /** Finansal oran servisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private FinansalOranService finansalOranService;

    /** Finansal tablo servisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private FinansalTabloService finansalTabloService;

    /** Temel analiz skor servisi (Piotroski, Altman). Bean yoksa {@code null}. */
    @Autowired(required = false)
    private TemelAnalizSkorService temelAnalizSkorService;

    /** Açığa satış servisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private AcigaSatisService acigaSatisService;

    /** VBTS tedbir servisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private VbtsTedbirService vbtsTedbirService;

    /** Sektörel karşılaştırma servisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private SektorelKarsilastirmaService sektorelKarsilastirmaService;

    /**
     * Belirtilen hisse için temel analiz sinyallerini üretir.
     *
     * <p>Her sinyal kuralı bağımsız çalışır. Bir kural başarısız olursa
     * diğerleri yine sinyal üretmeye devam eder.</p>
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return sinyal listesi (boş olabilir)
     */
    public List<TemelAnalizSinyalDto> getSinyaller(String stockCode) {
        List<TemelAnalizSinyalDto> sinyaller = new ArrayList<>();
        LocalDate bugun = LocalDate.now(ISTANBUL_ZONE);

        // 1. Düşük F/K
        try {
            kontrolDusukFk(stockCode, bugun, sinyaller);
        } catch (Exception e) {
            log.debug("[TEMEL-SINYAL] Düşük F/K kontrolü başarısız: stockCode={}", stockCode);
        }

        // 2. Güçlü ROE
        try {
            kontrolGucluRoe(stockCode, bugun, sinyaller);
        } catch (Exception e) {
            log.debug("[TEMEL-SINYAL] Güçlü ROE kontrolü başarısız: stockCode={}", stockCode);
        }

        // 3. Borç Azaltma
        try {
            kontrolBorcAzaltma(stockCode, bugun, sinyaller);
        } catch (Exception e) {
            log.debug("[TEMEL-SINYAL] Borç azaltma kontrolü başarısız: stockCode={}", stockCode);
        }

        // 4. Değer Fırsatı (PD/DD < 1 ve net kâr > 0)
        try {
            kontrolDegerFirsati(stockCode, bugun, sinyaller);
        } catch (Exception e) {
            log.debug("[TEMEL-SINYAL] Değer fırsatı kontrolü başarısız: stockCode={}", stockCode);
        }

        // 5. Güçlü Temel (Piotroski >= 7)
        try {
            kontrolGucluTemel(stockCode, bugun, sinyaller);
        } catch (Exception e) {
            log.debug("[TEMEL-SINYAL] Güçlü temel kontrolü başarısız: stockCode={}", stockCode);
        }

        // 6. İflas Riski (Altman < 1.81)
        try {
            kontrolIflasRiski(stockCode, bugun, sinyaller);
        } catch (Exception e) {
            log.debug("[TEMEL-SINYAL] İflas riski kontrolü başarısız: stockCode={}", stockCode);
        }

        // 7. VBTS Tedbiri
        try {
            kontrolVbtsTedbiri(stockCode, bugun, sinyaller);
        } catch (Exception e) {
            log.debug("[TEMEL-SINYAL] VBTS tedbir kontrolü başarısız: stockCode={}", stockCode);
        }

        // 8. Yüksek Açığa Satış
        try {
            kontrolAcigaSatis(stockCode, bugun, sinyaller);
        } catch (Exception e) {
            log.debug("[TEMEL-SINYAL] Açığa satış kontrolü başarısız: stockCode={}", stockCode);
        }

        return sinyaller;
    }

    /**
     * F/K &lt; sektör medyanı * 0.7 → POZİTİF "Düşük F/K".
     */
    private void kontrolDusukFk(String stockCode, LocalDate bugun, List<TemelAnalizSinyalDto> sinyaller) {
        if (finansalOranService == null || sektorelKarsilastirmaService == null) {
            return;
        }

        SektorelKarsilastirmaDto karsilastirma = sektorelKarsilastirmaService.getSektorelKarsilastirma(stockCode);
        if (karsilastirma == null || karsilastirma.getSirketOranlari() == null
                || karsilastirma.getSektorMedian() == null) {
            return;
        }

        Double sirketFk = karsilastirma.getSirketOranlari().get("F/K");
        Double sektorMedianFk = karsilastirma.getSektorMedian().get("F/K");

        if (sirketFk == null || sektorMedianFk == null || sektorMedianFk <= 0 || sirketFk <= 0) {
            return;
        }

        if (sirketFk < sektorMedianFk * FK_UCUZ_CARPAN) {
            sinyaller.add(TemelAnalizSinyalDto.builder()
                    .hisseSenediKodu(stockCode)
                    .sinyalTipi("DUSUK_FK")
                    .sinyalAciklama(String.format("F/K (%.2f) sektör medyanının (%.2f) %%70'inin altında",
                            sirketFk, sektorMedianFk))
                    .sinyalYonu("POZITIF")
                    .tarih(bugun)
                    .deger(sirketFk)
                    .build());
        }
    }

    /**
     * ROE &gt; %15 ve artan → POZİTİF "Güçlü ROE".
     */
    private void kontrolGucluRoe(String stockCode, LocalDate bugun, List<TemelAnalizSinyalDto> sinyaller) {
        if (finansalOranService == null) {
            return;
        }

        List<FinansalOranDto> oranlar = finansalOranService.getHisseOranlar(stockCode);
        List<FinansalOranDto> roeList = oranlar.stream()
                .filter(o -> "ROE".equals(o.getOran()))
                .filter(o -> o.getDeger() != null && o.getYil() != null)
                .sorted(Comparator.comparingInt(FinansalOranDto::getYil)
                        .thenComparingInt(o -> o.getAy() != null ? o.getAy() : 0))
                .collect(Collectors.toList());

        if (roeList.size() < 2) {
            return;
        }

        FinansalOranDto son = roeList.get(roeList.size() - 1);
        FinansalOranDto onceki = roeList.get(roeList.size() - 2);

        if (son.getDeger() > ROE_GUCLU_ESIK && son.getDeger() > onceki.getDeger()) {
            sinyaller.add(TemelAnalizSinyalDto.builder()
                    .hisseSenediKodu(stockCode)
                    .sinyalTipi("GUCLU_ROE")
                    .sinyalAciklama(String.format("ROE (%%%.1f) > %%15 ve artış eğiliminde", son.getDeger()))
                    .sinyalYonu("POZITIF")
                    .tarih(bugun)
                    .deger(son.getDeger())
                    .build());
        }
    }

    /**
     * Net Borç/FAVÖK azalan → POZİTİF "Borç Azaltma".
     */
    private void kontrolBorcAzaltma(String stockCode, LocalDate bugun, List<TemelAnalizSinyalDto> sinyaller) {
        if (finansalOranService == null) {
            return;
        }

        List<FinansalOranDto> oranlar = finansalOranService.getHisseOranlar(stockCode);
        List<FinansalOranDto> borcList = oranlar.stream()
                .filter(o -> o.getOran() != null && o.getOran().contains("Net Borç"))
                .filter(o -> o.getDeger() != null && o.getYil() != null)
                .sorted(Comparator.comparingInt(FinansalOranDto::getYil)
                        .thenComparingInt(o -> o.getAy() != null ? o.getAy() : 0))
                .collect(Collectors.toList());

        if (borcList.size() < 2) {
            return;
        }

        FinansalOranDto son = borcList.get(borcList.size() - 1);
        FinansalOranDto onceki = borcList.get(borcList.size() - 2);

        if (son.getDeger() < onceki.getDeger()) {
            sinyaller.add(TemelAnalizSinyalDto.builder()
                    .hisseSenediKodu(stockCode)
                    .sinyalTipi("BORC_AZALTMA")
                    .sinyalAciklama(String.format("Net Borç/FAVÖK %.2f'den %.2f'e geriledi",
                            onceki.getDeger(), son.getDeger()))
                    .sinyalYonu("POZITIF")
                    .tarih(bugun)
                    .deger(son.getDeger())
                    .build());
        }
    }

    /**
     * PD/DD &lt; 1 ve net kâr &gt; 0 → POZİTİF "Değer Fırsatı".
     */
    private void kontrolDegerFirsati(String stockCode, LocalDate bugun, List<TemelAnalizSinyalDto> sinyaller) {
        if (finansalOranService == null || finansalTabloService == null) {
            return;
        }

        List<FinansalOranDto> oranlar = finansalOranService.getHisseOranlar(stockCode);
        Optional<FinansalOranDto> pdddOpt = oranlar.stream()
                .filter(o -> "PD/DD".equals(o.getOran()))
                .filter(o -> o.getDeger() != null)
                .max(Comparator.comparingInt(FinansalOranDto::getYil));

        if (pdddOpt.isEmpty() || pdddOpt.get().getDeger() >= 1.0) {
            return;
        }

        // Net kâr kontrolü
        List<FinansalTabloDto> gelirTablo = finansalTabloService.getHisseGelirTablosu(stockCode);
        Optional<FinansalTabloDto> netKarOpt = gelirTablo.stream()
                .filter(t -> "Net Dönem Karı veya Zararı".equalsIgnoreCase(t.getKalem()))
                .filter(t -> Integer.valueOf(12).equals(t.getAy()))
                .filter(t -> t.getTryDonemsel() != null)
                .max(Comparator.comparingInt(FinansalTabloDto::getYil));

        if (netKarOpt.isPresent() && netKarOpt.get().getTryDonemsel() > 0) {
            sinyaller.add(TemelAnalizSinyalDto.builder()
                    .hisseSenediKodu(stockCode)
                    .sinyalTipi("DEGER_FIRSATI")
                    .sinyalAciklama(String.format("PD/DD (%.2f) < 1 ve net kâr pozitif",
                            pdddOpt.get().getDeger()))
                    .sinyalYonu("POZITIF")
                    .tarih(bugun)
                    .deger(pdddOpt.get().getDeger())
                    .build());
        }
    }

    /**
     * Piotroski F-Score &ge; 7 → POZİTİF "Güçlü Temel".
     */
    private void kontrolGucluTemel(String stockCode, LocalDate bugun, List<TemelAnalizSinyalDto> sinyaller) {
        if (temelAnalizSkorService == null) {
            return;
        }

        TemelAnalizSkorDto skorlar = temelAnalizSkorService.calculateScores(stockCode);
        if (skorlar == null || skorlar.getPiotroskiFScore() == null) {
            return;
        }

        if (skorlar.getPiotroskiFScore() >= PIOTROSKI_GUCLU_ESIK) {
            sinyaller.add(TemelAnalizSinyalDto.builder()
                    .hisseSenediKodu(stockCode)
                    .sinyalTipi("GUCLU_TEMEL")
                    .sinyalAciklama(String.format("Piotroski F-Score: %d/9 (güçlü finansal sağlık)",
                            skorlar.getPiotroskiFScore()))
                    .sinyalYonu("POZITIF")
                    .tarih(bugun)
                    .deger((double) skorlar.getPiotroskiFScore())
                    .build());
        }
    }

    /**
     * Altman Z-Score &lt; 1.81 → NEGATİF "İflas Riski".
     */
    private void kontrolIflasRiski(String stockCode, LocalDate bugun, List<TemelAnalizSinyalDto> sinyaller) {
        if (temelAnalizSkorService == null) {
            return;
        }

        TemelAnalizSkorDto skorlar = temelAnalizSkorService.calculateScores(stockCode);
        if (skorlar == null || skorlar.getAltmanZScore() == null) {
            return;
        }

        if (skorlar.getAltmanZScore() < ALTMAN_TEHLIKE_ESIK) {
            sinyaller.add(TemelAnalizSinyalDto.builder()
                    .hisseSenediKodu(stockCode)
                    .sinyalTipi("IFLAS_RISKI")
                    .sinyalAciklama(String.format("Altman Z-Score: %.2f (tehlike bölgesi < 1.81)",
                            skorlar.getAltmanZScore()))
                    .sinyalYonu("NEGATIF")
                    .tarih(bugun)
                    .deger(skorlar.getAltmanZScore())
                    .build());
        }
    }

    /**
     * VBTS tedbirli → NEGATİF "VBTS Tedbiri".
     */
    private void kontrolVbtsTedbiri(String stockCode, LocalDate bugun, List<TemelAnalizSinyalDto> sinyaller) {
        if (vbtsTedbirService == null) {
            return;
        }

        List<VbtsTedbirDto> tedbirler = vbtsTedbirService.getAktifTedbirler();
        if (tedbirler == null) {
            return;
        }

        boolean tedbirli = tedbirler.stream()
                .anyMatch(t -> stockCode.equalsIgnoreCase(t.getHisseSenediKodu()));

        if (tedbirli) {
            sinyaller.add(TemelAnalizSinyalDto.builder()
                    .hisseSenediKodu(stockCode)
                    .sinyalTipi("VBTS_TEDBIRI")
                    .sinyalAciklama("Hisse VBTS tedbirli listesinde")
                    .sinyalYonu("NEGATIF")
                    .tarih(bugun)
                    .deger(null)
                    .build());
        }
    }

    /**
     * Açığa satış oranı yüksek → NEGATİF "Yüksek Açığa Satış".
     */
    private void kontrolAcigaSatis(String stockCode, LocalDate bugun, List<TemelAnalizSinyalDto> sinyaller) {
        if (acigaSatisService == null) {
            return;
        }

        List<AcigaSatisDto> acigaSatislar = acigaSatisService.getHisseAcigaSatis(stockCode);
        if (acigaSatislar == null || acigaSatislar.isEmpty()) {
            return;
        }

        // En son tarihli kaydı al
        AcigaSatisDto hisse = acigaSatislar.stream()
                .filter(a -> a.getTarih() != null)
                .max(java.util.Comparator.comparing(AcigaSatisDto::getTarih))
                .orElse(acigaSatislar.get(0));

        // Açığa satış oranı = açığa satış lotu / toplam işlem hacmi lotu * 100
        if (hisse.getAcigaSatisLotu() == null || hisse.getToplamIslemHacmiLot() == null
                || hisse.getToplamIslemHacmiLot() == 0) {
            return;
        }

        double oran = (double) hisse.getAcigaSatisLotu() / hisse.getToplamIslemHacmiLot() * 100;
        if (oran > ACIGA_SATIS_YUKSEK_ESIK) {
            sinyaller.add(TemelAnalizSinyalDto.builder()
                    .hisseSenediKodu(stockCode)
                    .sinyalTipi("YUKSEK_ACIGA_SATIS")
                    .sinyalAciklama(String.format("Açığa satış oranı: %%%.1f (> %%%.1f eşiği)",
                            oran, ACIGA_SATIS_YUKSEK_ESIK))
                    .sinyalYonu("NEGATIF")
                    .tarih(bugun)
                    .deger(oran)
                    .build());
        }
    }
}
