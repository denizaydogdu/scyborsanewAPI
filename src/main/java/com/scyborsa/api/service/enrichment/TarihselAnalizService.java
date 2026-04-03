package com.scyborsa.api.service.enrichment;

import com.scyborsa.api.dto.fintables.FinansalOranDto;
import com.scyborsa.api.dto.fintables.FinansalTabloDto;
import com.scyborsa.api.dto.fintables.TarihselAnalizDto;
import com.scyborsa.api.dto.fintables.TarihselAnalizDto.DonemOranDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Tarihsel analiz servisi.
 *
 * <p>Bir hissenin tarihsel F/K bandı, gelir/net kâr CAGR (bileşik yıllık
 * büyüme oranı) ve net borç trendi hesaplamalarını gerçekleştirir.</p>
 *
 * <p>Tüm bileşenler bağımsız try-catch ile sarılıdır; bir bileşen
 * hesaplanamazsa diğerleri yine döner (graceful degradation).</p>
 *
 * @see TarihselAnalizDto
 * @see FinansalOranService
 * @see FinansalTabloService
 */
@Slf4j
@Service
public class TarihselAnalizService {

    /** Istanbul saat dilimi. */
    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");

    /** CAGR hesaplama süresi (yıl). */
    private static final int CAGR_YEARS = 5;

    /** F/K oran adı (FinansalOranDto'daki "oran" alanı). */
    private static final String FK_ORAN_ADI = "F/K";

    /** Hasılat kalem adı (gelir tablosu). */
    private static final String HASILAT_KALEM = "Hasılat";

    /** Net kâr kalem adı (gelir tablosu). */
    private static final String NET_KAR_KALEM = "Net Dönem Karı veya Zararı";

    /** Toplam Finansal Borçlar kalem adı (bilanço). */
    private static final String FINANSAL_BORCLAR_KALEM = "Toplam Finansal Borçlar";

    /** Nakit ve Nakit Benzerleri kalem adı (bilanço). */
    private static final String NAKIT_KALEM = "Nakit ve Nakit Benzerleri";

    /** Finansal oran servisi (F/K verileri). Bean yoksa {@code null}. */
    @Autowired(required = false)
    private FinansalOranService finansalOranService;

    /** Finansal tablo servisi (bilanço, gelir tablosu). Bean yoksa {@code null}. */
    @Autowired(required = false)
    private FinansalTabloService finansalTabloService;

    /**
     * Belirtilen hisse için tarihsel analiz verisini hesaplar.
     *
     * <p>F/K bandı, gelir CAGR, net kâr CAGR ve net borç trendi bağımsız
     * olarak hesaplanır. Hesaplanamayan bileşenler {@code null} kalır.</p>
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return tarihsel analiz DTO'su
     */
    public TarihselAnalizDto getTarihselAnaliz(String stockCode) {
        TarihselAnalizDto.TarihselAnalizDtoBuilder builder = TarihselAnalizDto.builder()
                .hisseSenediKodu(stockCode);

        // 1. F/K bandı
        try {
            builder.fkBandi(hesaplaFkBandi(stockCode));
        } catch (Exception e) {
            log.warn("[TARIHSEL-ANALIZ] F/K bandı hesaplanamadı: stockCode={}", stockCode, e);
        }

        // 2. Gelir CAGR
        try {
            builder.gelirCagr5Yil(hesaplaCagr(stockCode, HASILAT_KALEM));
        } catch (Exception e) {
            log.warn("[TARIHSEL-ANALIZ] Gelir CAGR hesaplanamadı: stockCode={}", stockCode, e);
        }

        // 3. Net Kâr CAGR
        try {
            builder.netKarCagr5Yil(hesaplaCagr(stockCode, NET_KAR_KALEM));
        } catch (Exception e) {
            log.warn("[TARIHSEL-ANALIZ] Net kâr CAGR hesaplanamadı: stockCode={}", stockCode, e);
        }

        // 4. Net Borç Trend
        try {
            builder.netBorcTrend(hesaplaNetBorcTrend(stockCode));
        } catch (Exception e) {
            log.warn("[TARIHSEL-ANALIZ] Net borç trendi hesaplanamadı: stockCode={}", stockCode, e);
        }

        return builder.build();
    }

    /**
     * Son 5 yılın çeyreklik F/K oranlarını listeler.
     *
     * @param stockCode hisse kodu
     * @return F/K bandı dönem listesi (yıl DESC, ay DESC), veri yoksa boş liste
     */
    private List<DonemOranDto> hesaplaFkBandi(String stockCode) {
        if (finansalOranService == null) {
            return Collections.emptyList();
        }

        int currentYear = LocalDate.now(ISTANBUL_ZONE).getYear();
        int minYear = currentYear - CAGR_YEARS;

        List<FinansalOranDto> oranlar = finansalOranService.getHisseOranlar(stockCode);
        return oranlar.stream()
                .filter(o -> FK_ORAN_ADI.equals(o.getOran()))
                .filter(o -> o.getYil() != null && o.getYil() >= minYear)
                .filter(o -> o.getDeger() != null)
                .sorted(Comparator.comparingInt(FinansalOranDto::getYil)
                        .thenComparingInt(FinansalOranDto::getAy))
                .map(o -> DonemOranDto.builder()
                        .yil(o.getYil())
                        .ay(o.getAy())
                        .deger(o.getDeger())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * Belirtilen kalem için 5 yıllık CAGR hesaplar.
     *
     * <p>Formül: (Son yıl değer / 5 yıl önce değer)^(1/5) - 1</p>
     *
     * @param stockCode hisse kodu
     * @param kalemAdi  gelir tablosu kalem adı (ör: "Hasılat", "Net Dönem Karı veya Zararı")
     * @return CAGR değeri (ör: 0.15 = %15), veri yetersizse {@code null}
     */
    private Double hesaplaCagr(String stockCode, String kalemAdi) {
        if (finansalTabloService == null) {
            return null;
        }

        List<FinansalTabloDto> gelirTablo = finansalTabloService.getHisseGelirTablosu(stockCode);
        if (gelirTablo.isEmpty()) {
            return null;
        }

        // Yıllık verileri al (ay=12 → yıllık dönemsel)
        Map<Integer, Long> yillikDegerler = gelirTablo.stream()
                .filter(t -> kalemAdi.equalsIgnoreCase(t.getKalem()))
                .filter(t -> t.getYil() != null && Integer.valueOf(12).equals(t.getAy()))
                .filter(t -> t.getTryDonemsel() != null && t.getTryDonemsel() != 0)
                .collect(Collectors.toMap(
                        FinansalTabloDto::getYil,
                        FinansalTabloDto::getTryDonemsel,
                        (a, b) -> b // son geleni al (duplicate key durumunda)
                ));

        if (yillikDegerler.size() < 2) {
            return null;
        }

        int maxYil = yillikDegerler.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        int minYil = maxYil - CAGR_YEARS;

        // En yakın başlangıç yılını bul
        Long sonDeger = yillikDegerler.get(maxYil);
        Long baslangicDeger = null;
        int baslangicYil = minYil;
        for (int y = minYil; y <= minYil + 2; y++) {
            if (yillikDegerler.containsKey(y)) {
                baslangicDeger = yillikDegerler.get(y);
                baslangicYil = y;
                break;
            }
        }

        if (sonDeger == null || baslangicDeger == null || baslangicDeger == 0) {
            return null;
        }

        int yilFarki = maxYil - baslangicYil;
        if (yilFarki <= 0) {
            return null;
        }

        // Negatif değerler için CAGR anlamlı değil
        if (sonDeger <= 0 || baslangicDeger <= 0) {
            return null;
        }

        double ratio = (double) sonDeger / baslangicDeger;
        return Math.pow(ratio, 1.0 / yilFarki) - 1;
    }

    /**
     * Her çeyrek için net borç trendi hesaplar.
     *
     * <p>Net Borç = Toplam Finansal Borçlar - Nakit ve Nakit Benzerleri</p>
     *
     * @param stockCode hisse kodu
     * @return net borç trendi dönem listesi (yıl ASC, ay ASC), veri yoksa boş liste
     */
    private List<DonemOranDto> hesaplaNetBorcTrend(String stockCode) {
        if (finansalTabloService == null) {
            return Collections.emptyList();
        }

        List<FinansalTabloDto> bilanco = finansalTabloService.getHisseBilanco(stockCode);
        if (bilanco.isEmpty()) {
            return Collections.emptyList();
        }

        // Dönem bazında finansal borç ve nakit değerlerini topla
        Map<String, Long> borcMap = new HashMap<>();
        Map<String, Long> nakitMap = new HashMap<>();

        for (FinansalTabloDto t : bilanco) {
            if (t.getYil() == null || t.getAy() == null || t.getTryDonemsel() == null) {
                continue;
            }
            String key = t.getYil() + "-" + t.getAy();
            if (FINANSAL_BORCLAR_KALEM.equalsIgnoreCase(t.getKalem())) {
                borcMap.put(key, t.getTryDonemsel());
            } else if (NAKIT_KALEM.equalsIgnoreCase(t.getKalem())) {
                nakitMap.put(key, t.getTryDonemsel());
            }
        }

        // Net borç = borç - nakit
        List<DonemOranDto> trend = new ArrayList<>();
        for (Map.Entry<String, Long> entry : borcMap.entrySet()) {
            Long nakit = nakitMap.get(entry.getKey());
            if (nakit == null) {
                continue;
            }
            String[] parts = entry.getKey().split("-");
            int yil = Integer.parseInt(parts[0]);
            int ay = Integer.parseInt(parts[1]);
            double netBorc = entry.getValue() - nakit;
            trend.add(DonemOranDto.builder()
                    .yil(yil)
                    .ay(ay)
                    .deger(netBorc)
                    .build());
        }

        trend.sort(Comparator.comparingInt(DonemOranDto::getYil)
                .thenComparingInt(DonemOranDto::getAy));

        return trend;
    }
}
