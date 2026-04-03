package com.scyborsa.api.service.enrichment;

import com.scyborsa.api.dto.fintables.EpsSurprizDto;
import com.scyborsa.api.dto.fintables.EpsSurprizDto.EpsDonemDto;
import com.scyborsa.api.dto.fintables.FinansalOranDto;
import com.scyborsa.api.dto.fintables.FinansalTabloDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * EPS sürpriz ve temettü sürdürülebilirlik servisi.
 *
 * <p>Analist tahmin EPS ile gerçekleşen EPS karşılaştırması, payout ratio,
 * FCF yield ve temettü sürdürülebilirlik değerlendirmesi yapar.</p>
 *
 * <p>Tüm bileşenler bağımsız try-catch ile sarılıdır; bir bileşen
 * hesaplanamazsa diğerleri yine döner (graceful degradation).</p>
 *
 * @see EpsSurprizDto
 * @see FinansalOranService
 * @see FinansalTabloService
 * @see HedefFiyatService
 */
@Slf4j
@Service
public class EpsSurprizService {

    /** Hisse Başına Kazanç oran adı. */
    private static final String HBK_ORAN_ADI = "Hisse Başına Kazanç";

    /** Temettü kalem adı (gelir tablosu). */
    private static final String TEMETTU_KALEM = "Temettü";

    /** Net kâr kalem adı. */
    private static final String NET_KAR_KALEM = "Net Dönem Karı veya Zararı";

    /** İşletme faaliyetlerinden nakit akımı kalem adı. */
    private static final String ISLETME_NAKIT_KALEM = "İşletme Faaliyetlerinden Nakit Akışları";

    /** Yatırım harcaması kalem adı (nakit akım). */
    private static final String YATIRIM_HARCAMA_KALEM = "Yatırım Faaliyetlerinden Kaynaklanan Nakit Akışları";

    /** Piyasa Değeri oran adı. */
    private static final String PIYASA_DEGERI_ORAN = "Piyasa Değeri";

    /** Sürdürülebilir payout ratio üst sınırı. */
    private static final double PAYOUT_SURDURULEBILIR_ESIK = 0.6;

    /** Finansal oran servisi (EPS, piyasa değeri). Bean yoksa {@code null}. */
    @Autowired(required = false)
    private FinansalOranService finansalOranService;

    /** Finansal tablo servisi (gelir tablosu, nakit akım). Bean yoksa {@code null}. */
    @Autowired(required = false)
    private FinansalTabloService finansalTabloService;

    /** Analist hedef fiyat servisi (tahmin EPS). Bean yoksa {@code null}. */
    @Autowired(required = false)
    private HedefFiyatService hedefFiyatService;

    /**
     * Belirtilen hisse için EPS sürpriz ve temettü sürdürülebilirlik verisini hesaplar.
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return EPS sürpriz DTO'su
     */
    public EpsSurprizDto getEpsSurpriz(String stockCode) {
        EpsSurprizDto.EpsSurprizDtoBuilder builder = EpsSurprizDto.builder()
                .hisseSenediKodu(stockCode);

        // 1. EPS sürpriz dönemleri
        try {
            builder.epsDonemler(hesaplaEpsSurpriz(stockCode));
        } catch (Exception e) {
            log.warn("[EPS-SURPRIZ] EPS sürpriz hesaplanamadı: stockCode={}", stockCode, e);
        }

        // 2. Payout ratio
        Double payoutRatio = null;
        try {
            payoutRatio = hesaplaPayoutRatio(stockCode);
            builder.payoutRatio(payoutRatio);
        } catch (Exception e) {
            log.warn("[EPS-SURPRIZ] Payout ratio hesaplanamadı: stockCode={}", stockCode, e);
        }

        // 3. FCF yield
        Double fcfYield = null;
        try {
            fcfYield = hesaplaFcfYield(stockCode);
            builder.fcfYield(fcfYield);
        } catch (Exception e) {
            log.warn("[EPS-SURPRIZ] FCF yield hesaplanamadı: stockCode={}", stockCode, e);
        }

        // 4. Temettü sürdürülebilirlik
        try {
            builder.temettuSurdurulebilirlik(degerlendir(payoutRatio, fcfYield));
        } catch (Exception e) {
            log.warn("[EPS-SURPRIZ] Sürdürülebilirlik değerlendirilemedi: stockCode={}", stockCode, e);
        }

        return builder.build();
    }

    /**
     * Gerçekleşen EPS trendi hesaplar.
     *
     * <p>Analist tahmin verisi henüz entegre edilmemiş; sadece gerçekleşen EPS
     * trendi gösterilir. {@code tahminEps} ve {@code surprizOrani} alanları
     * {@code null} döner.</p>
     *
     * @param stockCode hisse kodu
     * @return EPS dönem listesi (sadece gerçekleşen), veri yoksa boş liste
     */
    private List<EpsDonemDto> hesaplaEpsSurpriz(String stockCode) {
        if (finansalOranService == null) {
            return Collections.emptyList();
        }

        // Gerçekleşen EPS (Hisse Başına Kazanç oranı)
        List<FinansalOranDto> oranlar = finansalOranService.getHisseOranlar(stockCode);
        Map<String, Double> gercekEpsMap = oranlar.stream()
                .filter(o -> HBK_ORAN_ADI.equals(o.getOran()))
                .filter(o -> o.getYil() != null && o.getAy() != null && o.getDeger() != null)
                .collect(Collectors.toMap(
                        o -> o.getYil() + "-" + o.getAy(),
                        FinansalOranDto::getDeger,
                        (a, b) -> b
                ));

        if (gercekEpsMap.isEmpty()) {
            return Collections.emptyList();
        }

        List<EpsDonemDto> donemler = new ArrayList<>();
        for (Map.Entry<String, Double> entry : gercekEpsMap.entrySet()) {
            String[] parts = entry.getKey().split("-");
            int yil = Integer.parseInt(parts[0]);
            int ay = Integer.parseInt(parts[1]);

            donemler.add(EpsDonemDto.builder()
                    .yil(yil)
                    .ay(ay)
                    .tahminEps(null)
                    .gercekEps(entry.getValue())
                    .surprizOrani(null)
                    .build());
        }

        donemler.sort(Comparator.comparingInt(EpsDonemDto::getYil)
                .thenComparingInt(EpsDonemDto::getAy));

        return donemler;
    }

    /**
     * Temettü dağıtım oranını hesaplar (Payout Ratio = Temettü / Net Kâr).
     *
     * @param stockCode hisse kodu
     * @return payout ratio, hesaplanamazsa {@code null}
     */
    private Double hesaplaPayoutRatio(String stockCode) {
        if (finansalTabloService == null) {
            return null;
        }

        List<FinansalTabloDto> gelirTablo = finansalTabloService.getHisseGelirTablosu(stockCode);
        if (gelirTablo.isEmpty()) {
            return null;
        }

        // En son yıllık dönem (ay=12)
        Optional<FinansalTabloDto> netKarOpt = gelirTablo.stream()
                .filter(t -> NET_KAR_KALEM.equalsIgnoreCase(t.getKalem()))
                .filter(t -> Integer.valueOf(12).equals(t.getAy()))
                .filter(t -> t.getTryDonemsel() != null && t.getTryDonemsel() != 0)
                .max(Comparator.comparingInt(FinansalTabloDto::getYil));

        if (netKarOpt.isEmpty()) {
            return null;
        }

        Long netKar = netKarOpt.get().getTryDonemsel();

        // Temettü kalemi — bazı şirketlerde olmayabilir
        Optional<FinansalTabloDto> temettuOpt = gelirTablo.stream()
                .filter(t -> t.getKalem() != null && t.getKalem().toLowerCase().contains("temettü"))
                .filter(t -> t.getYil() != null && t.getYil().equals(netKarOpt.get().getYil()))
                .filter(t -> Integer.valueOf(12).equals(t.getAy()))
                .filter(t -> t.getTryDonemsel() != null)
                .findFirst();

        if (temettuOpt.isEmpty()) {
            return null;
        }

        return Math.abs((double) temettuOpt.get().getTryDonemsel()) / Math.abs(netKar);
    }

    /**
     * Serbest nakit akım getirisini hesaplar (FCF Yield = FCF / Piyasa Değeri).
     *
     * <p>FCF = İşletme Nakit Akımı - Yatırım Harcaması (mutlak değer)</p>
     *
     * @param stockCode hisse kodu
     * @return FCF yield, hesaplanamazsa {@code null}
     */
    private Double hesaplaFcfYield(String stockCode) {
        if (finansalTabloService == null || finansalOranService == null) {
            return null;
        }

        List<FinansalTabloDto> nakitAkim = finansalTabloService.getHisseNakitAkim(stockCode);
        if (nakitAkim.isEmpty()) {
            return null;
        }

        // Son yıllık dönem (ay=12)
        Optional<FinansalTabloDto> isletmeOpt = nakitAkim.stream()
                .filter(t -> ISLETME_NAKIT_KALEM.equalsIgnoreCase(t.getKalem()))
                .filter(t -> Integer.valueOf(12).equals(t.getAy()))
                .filter(t -> t.getTryDonemsel() != null)
                .max(Comparator.comparingInt(FinansalTabloDto::getYil));

        if (isletmeOpt.isEmpty()) {
            return null;
        }

        int yil = isletmeOpt.get().getYil();
        long isletmeNakit = isletmeOpt.get().getTryDonemsel();

        Optional<FinansalTabloDto> yatirimOpt = nakitAkim.stream()
                .filter(t -> YATIRIM_HARCAMA_KALEM.equalsIgnoreCase(t.getKalem()))
                .filter(t -> t.getYil() != null && t.getYil().equals(yil))
                .filter(t -> Integer.valueOf(12).equals(t.getAy()))
                .filter(t -> t.getTryDonemsel() != null)
                .findFirst();

        long yatirimHarcama = yatirimOpt.map(FinansalTabloDto::getTryDonemsel).orElse(0L);

        // FCF = İşletme nakit akımı + Yatırım nakit akımı (yatırım genelde negatif)
        double fcf = isletmeNakit + yatirimHarcama;

        // Piyasa değeri
        List<FinansalOranDto> oranlar = finansalOranService.getHisseOranlar(stockCode);
        Optional<FinansalOranDto> pdOpt = oranlar.stream()
                .filter(o -> PIYASA_DEGERI_ORAN.equals(o.getOran()))
                .filter(o -> o.getDeger() != null && o.getDeger() > 0)
                .max(Comparator.comparingInt(FinansalOranDto::getYil));

        if (pdOpt.isEmpty()) {
            return null;
        }

        return fcf / pdOpt.get().getDeger();
    }

    /**
     * Temettü sürdürülebilirlik değerlendirmesi yapar.
     *
     * <ul>
     *   <li>{@code payoutRatio < 0.6} ve {@code fcfYield > 0} → "SURDURULEBILIR"</li>
     *   <li>Aksi halde ikisinden biri olumsuz → "RISKLI"</li>
     *   <li>Yetersiz veri → "BELIRSIZ"</li>
     * </ul>
     *
     * @param payoutRatio temettü dağıtım oranı
     * @param fcfYield    serbest nakit akım getirisi
     * @return "SURDURULEBILIR", "RISKLI" veya "BELIRSIZ"
     */
    private String degerlendir(Double payoutRatio, Double fcfYield) {
        if (payoutRatio == null && fcfYield == null) {
            return "BELIRSIZ";
        }

        boolean payoutOk = payoutRatio != null && payoutRatio < PAYOUT_SURDURULEBILIR_ESIK;
        boolean fcfOk = fcfYield != null && fcfYield > 0;

        if (payoutRatio != null && fcfYield != null) {
            return (payoutOk && fcfOk) ? "SURDURULEBILIR" : "RISKLI";
        }

        // Tek veri varsa
        if (payoutRatio != null) {
            return payoutOk ? "SURDURULEBILIR" : "RISKLI";
        }
        return fcfOk ? "SURDURULEBILIR" : "RISKLI";
    }
}
