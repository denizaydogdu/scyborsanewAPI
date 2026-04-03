package com.scyborsa.api.service.enrichment;

import com.scyborsa.api.dto.fintables.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Portföy vs BIST100 overlay karşılaştırma servisi.
 *
 * <p>Kullanıcının portföyündeki hisselerin ortalama F/K ve ROE değerlerini
 * BIST100 ortalaması ile karşılaştırır. Pozitif/negatif sinyal sayılarını toplar
 * ve genel bir değerlendirme ("GÜÇLÜ"/"ORTA"/"ZAYIF") üretir.</p>
 *
 * <p>Değerlendirme mantığı:</p>
 * <ul>
 *   <li>Portföy F/K &lt; BIST100 F/K VE Portföy ROE &gt; BIST100 ROE → "GÜÇLÜ"</li>
 *   <li>Portföy F/K &gt; BIST100 F/K VE Portföy ROE &lt; BIST100 ROE → "ZAYIF"</li>
 *   <li>Diğer durumlar → "ORTA"</li>
 * </ul>
 *
 * @see PortfoyOverlayDto
 * @see PortfoyZenginlestirmeService
 */
@Slf4j
@Service
public class PortfoyOverlayService {

    /** Portföy zenginleştirme servisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private PortfoyZenginlestirmeService portfoyZenginlestirmeService;

    /** Finansal oran servisi. Bean yoksa {@code null}. */
    @Autowired(required = false)
    private FinansalOranService finansalOranService;

    /**
     * Portföy hisselerinin ortalama temel verilerini BIST100 ile karşılaştırır.
     *
     * <p>Her hesaplama adımı bağımsız try-catch ile sarılıdır (graceful degradation).
     * Veri eksikliğinde ilgili alan {@code null} kalır.</p>
     *
     * @param stockCodes portföydeki hisse kodları listesi
     * @return {@link PortfoyOverlayDto} karşılaştırma sonucu
     */
    public PortfoyOverlayDto hesaplaOverlay(List<String> stockCodes) {
        log.debug("[PORTFOY-OVERLAY] Overlay hesaplama: {} hisse", stockCodes != null ? stockCodes.size() : 0);

        PortfoyOverlayDto.PortfoyOverlayDtoBuilder builder = PortfoyOverlayDto.builder();

        // Portföy ortalamaları
        Double portfoyFk = null;
        Double portfoyRoe = null;
        int pozitifSinyal = 0;
        int negatifSinyal = 0;

        try {
            if (portfoyZenginlestirmeService != null && stockCodes != null && !stockCodes.isEmpty()) {
                List<PortfoyTemelVeriDto> veriler = portfoyZenginlestirmeService.zenginlestirToplu(stockCodes);

                // F/K ortalaması
                double fkToplam = 0;
                int fkSayac = 0;
                double roeToplam = 0;
                int roeSayac = 0;

                for (PortfoyTemelVeriDto veri : veriler) {
                    if (veri.getFk() != null) {
                        fkToplam += veri.getFk();
                        fkSayac++;
                    }
                    if (veri.getRoe() != null) {
                        roeToplam += veri.getRoe();
                        roeSayac++;
                    }
                    if (veri.getSinyalSayisi() != null) {
                        if (veri.getSinyalSayisi() > 0) {
                            pozitifSinyal += veri.getSinyalSayisi();
                        } else if (veri.getSinyalSayisi() < 0) {
                            negatifSinyal += Math.abs(veri.getSinyalSayisi());
                        }
                    }
                }

                portfoyFk = fkSayac > 0 ? fkToplam / fkSayac : null;
                portfoyRoe = roeSayac > 0 ? roeToplam / roeSayac : null;
            }
        } catch (Exception e) {
            log.warn("[PORTFOY-OVERLAY] Portföy ortalaması hesaplanamadı: hata={}", e.getMessage());
        }

        builder.portfoyOrtalamaFk(portfoyFk);
        builder.portfoyOrtalamaRoe(portfoyRoe);
        builder.portfoyPozitifSinyalSayisi(pozitifSinyal);
        builder.portfoyNegatifSinyalSayisi(negatifSinyal);

        // BIST100 ortalamaları
        Double bist100Fk = null;
        Double bist100Roe = null;

        try {
            if (finansalOranService != null) {
                List<FinansalOranDto> tumOranlar = finansalOranService.getFinansalOranlar();
                if (tumOranlar != null && !tumOranlar.isEmpty()) {
                    double fkToplam = 0;
                    int fkSayac = 0;
                    double roeToplam = 0;
                    int roeSayac = 0;

                    for (FinansalOranDto oran : tumOranlar) {
                        if (oran.getOran() == null || oran.getDeger() == null) continue;
                        if ("F/K".equals(oran.getOran())) {
                            fkToplam += oran.getDeger();
                            fkSayac++;
                        } else if ("ROE".equals(oran.getOran())) {
                            roeToplam += oran.getDeger();
                            roeSayac++;
                        }
                    }

                    bist100Fk = fkSayac > 0 ? fkToplam / fkSayac : null;
                    bist100Roe = roeSayac > 0 ? roeToplam / roeSayac : null;
                }
            }
        } catch (Exception e) {
            log.warn("[PORTFOY-OVERLAY] BIST100 ortalaması hesaplanamadı: hata={}", e.getMessage());
        }

        builder.bist100OrtalamaFk(bist100Fk);
        builder.bist100OrtalamaRoe(bist100Roe);

        // Genel değerlendirme
        String degerlendirme = hesaplaDegerlendirme(portfoyFk, bist100Fk, portfoyRoe, bist100Roe);
        builder.genelDegerlendirme(degerlendirme);

        return builder.build();
    }

    /**
     * Portföy ve BIST100 ortalamalarına göre genel değerlendirme hesaplar.
     *
     * @param portfoyFk    portföy ortalama F/K
     * @param bist100Fk    BIST100 ortalama F/K
     * @param portfoyRoe   portföy ortalama ROE
     * @param bist100Roe   BIST100 ortalama ROE
     * @return "GÜÇLÜ", "ORTA" veya "ZAYIF"
     */
    private String hesaplaDegerlendirme(Double portfoyFk, Double bist100Fk,
                                         Double portfoyRoe, Double bist100Roe) {
        if (portfoyFk == null || bist100Fk == null || portfoyRoe == null || bist100Roe == null) {
            return "ORTA";
        }

        boolean dusukFk = portfoyFk < bist100Fk;
        boolean yuksekRoe = portfoyRoe > bist100Roe;

        if (dusukFk && yuksekRoe) {
            return "GÜÇLÜ";
        } else if (!dusukFk && !yuksekRoe) {
            return "ZAYIF";
        }
        return "ORTA";
    }
}
