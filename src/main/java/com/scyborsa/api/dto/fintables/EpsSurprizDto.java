package com.scyborsa.api.dto.fintables;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * EPS sürpriz ve temettü sürdürülebilirlik DTO'su.
 *
 * <p>Bir hissenin dönemsel EPS sürpriz oranları, payout ratio, FCF yield
 * ve temettü sürdürülebilirlik değerlendirmesini taşır. Her bileşen bağımsız
 * hesaplanır; veri yoksa ilgili alan {@code null} döner (graceful degradation).</p>
 *
 * @see com.scyborsa.api.service.enrichment.EpsSurprizService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EpsSurprizDto {

    /** Hisse senedi kodu (ör: "GARAN"). */
    private String hisseSenediKodu;

    /** Dönemsel EPS sürpriz verileri. */
    private List<EpsDonemDto> epsDonemler;

    /** Temettü dağıtım oranı (Temettü / Net Kâr). */
    private Double payoutRatio;

    /** Serbest nakit akım getirisi (FCF / Piyasa Değeri). */
    private Double fcfYield;

    /** Temettü sürdürülebilirlik değerlendirmesi: "SURDURULEBILIR", "RISKLI" veya "BELIRSIZ". */
    private String temettuSurdurulebilirlik;

    /**
     * Dönemsel EPS sürpriz iç DTO'su.
     *
     * <p>Belirli bir dönem için tahmin edilen ve gerçekleşen EPS değerleri ile
     * sürpriz oranını taşır.</p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EpsDonemDto {

        /** Bilanço yılı. */
        private Integer yil;

        /** Bilanço ayı (3, 6, 9, 12). */
        private Integer ay;

        /** Analist tahmin EPS değeri. */
        private Double tahminEps;

        /** Gerçekleşen EPS değeri. */
        private Double gercekEps;

        /** Sürpriz oranı yüzdesi: (gerçek - tahmin) / |tahmin| * 100. */
        private Double surprizOrani;
    }
}
