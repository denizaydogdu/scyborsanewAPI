package com.scyborsa.api.service.enrichment;

import com.scyborsa.api.dto.fintables.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Portföy temel veri zenginleştirme servisi.
 *
 * <p>Bir hissenin F/K, PD/DD, ROE, temettü verimi, analist konsensüs,
 * hedef fiyat ve sinyal sayısı bilgilerini tek bir DTO'da toplar.
 * Her veri kaynağı bağımsız try-catch ile sarılıdır (graceful degradation).</p>
 *
 * <p>Veri kaynakları:</p>
 * <ul>
 *   <li>{@link FinansalOranService} — F/K, PD/DD, ROE</li>
 *   <li>{@link HedefFiyatService} — Analist hedef fiyat ve konsensüs</li>
 *   <li>{@link TemelAnalizSinyalService} — Sinyal sayısı</li>
 * </ul>
 *
 * @see PortfoyTemelVeriDto
 */
@Slf4j
@Service
public class PortfoyZenginlestirmeService {

    /** Finansal oran servisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private FinansalOranService finansalOranService;

    /** Hedef fiyat servisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private HedefFiyatService hedefFiyatService;

    /** Temel analiz sinyal servisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private TemelAnalizSinyalService temelAnalizSinyalService;

    /**
     * Belirtilen hisse için portföy temel verilerini zenginleştirir.
     *
     * <p>Her veri kaynağı bağımsız çalışır; biri başarısız olursa
     * diğerleri yine doldurulmaya devam eder.</p>
     *
     * @param stockCode hisse kodu (ör: "GARAN")
     * @return {@link PortfoyTemelVeriDto} zenginleştirilmiş temel veri
     */
    public PortfoyTemelVeriDto zenginlestir(String stockCode) {
        log.debug("[PORTFOY-ZENGIN] Zenginleştirme başlatıldı: stockCode={}", stockCode);

        PortfoyTemelVeriDto.PortfoyTemelVeriDtoBuilder builder = PortfoyTemelVeriDto.builder()
                .hisseSenediKodu(stockCode);

        // F/K, PD/DD, ROE — FinansalOranService (oran/deger çiftlerinden ayrıştırma)
        try {
            if (finansalOranService != null) {
                List<FinansalOranDto> oranlar = finansalOranService.getHisseOranlar(stockCode);
                if (oranlar != null) {
                    for (FinansalOranDto oran : oranlar) {
                        if (oran.getOran() == null || oran.getDeger() == null) continue;
                        switch (oran.getOran()) {
                            case "F/K" -> builder.fk(oran.getDeger());
                            case "PD/DD" -> builder.pdDd(oran.getDeger());
                            case "ROE" -> builder.roe(oran.getDeger());
                            default -> { /* diğer oranlar atlanır */ }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[PORTFOY-ZENGIN] Finansal oran alınamadı: stockCode={}, hata={}", stockCode, e.getMessage());
        }

        // Hedef fiyat ve konsensüs — HedefFiyatService
        try {
            if (hedefFiyatService != null) {
                List<HedefFiyatDto> hedefler = hedefFiyatService.getHisseHedefFiyatlar(stockCode);
                if (hedefler != null && !hedefler.isEmpty()) {
                    HedefFiyatDto son = hedefler.get(0);
                    builder.hedefFiyat(son.getHedefFiyat());
                    builder.analistKonsensus(son.getTavsiye());
                }
            }
        } catch (Exception e) {
            log.warn("[PORTFOY-ZENGIN] Hedef fiyat alınamadı: stockCode={}, hata={}", stockCode, e.getMessage());
        }

        // Sinyal sayısı — TemelAnalizSinyalService
        try {
            if (temelAnalizSinyalService != null) {
                List<TemelAnalizSinyalDto> sinyaller = temelAnalizSinyalService.getSinyaller(stockCode);
                builder.sinyalSayisi(sinyaller != null ? sinyaller.size() : 0);
            }
        } catch (Exception e) {
            log.warn("[PORTFOY-ZENGIN] Sinyal sayısı alınamadı: stockCode={}, hata={}", stockCode, e.getMessage());
        }

        return builder.build();
    }

    /**
     * Birden fazla hisse için toplu portföy temel veri zenginleştirmesi yapar.
     *
     * <p>Her hisse bağımsız zenginleştirilir; biri başarısız olursa
     * diğerleri yine işlenmeye devam eder.</p>
     *
     * @param stockCodes hisse kodu listesi (ör: ["GARAN", "THYAO"])
     * @return {@link PortfoyTemelVeriDto} listesi
     */
    public List<PortfoyTemelVeriDto> zenginlestirToplu(List<String> stockCodes) {
        if (stockCodes == null || stockCodes.isEmpty()) {
            return Collections.emptyList();
        }
        log.debug("[PORTFOY-ZENGIN] Toplu zenginleştirme: {} hisse", stockCodes.size());
        List<PortfoyTemelVeriDto> sonuclar = new ArrayList<>();
        for (String stockCode : stockCodes) {
            try {
                sonuclar.add(zenginlestir(stockCode));
            } catch (Exception e) {
                log.warn("[PORTFOY-ZENGIN] Toplu zenginleştirme hatası: stockCode={}, hata={}",
                        stockCode, e.getMessage());
            }
        }
        return sonuclar;
    }
}
