package com.scyborsa.api.dto.fintables;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Tarihsel analiz DTO'su.
 *
 * <p>Bir hissenin tarihsel F/K bandı, gelir/net kâr CAGR ve net borç
 * trend verilerini taşır. Her bileşen bağımsız hesaplanır; veri yoksa
 * ilgili alan {@code null} döner (graceful degradation).</p>
 *
 * @see com.scyborsa.api.service.enrichment.TarihselAnalizService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TarihselAnalizDto {

    /** Hisse senedi kodu (ör: "GARAN"). */
    private String hisseSenediKodu;

    /** Son 5 yılın çeyreklik F/K oranları listesi. */
    private List<DonemOranDto> fkBandi;

    /** Gelir (Hasılat) bileşik yıllık büyüme oranı — 5 yıllık CAGR. */
    private Double gelirCagr5Yil;

    /** Net kâr bileşik yıllık büyüme oranı — 5 yıllık CAGR. */
    private Double netKarCagr5Yil;

    /** Her çeyrek için net borç trendi (Toplam Finansal Borçlar - Nakit). */
    private List<DonemOranDto> netBorcTrend;

    /**
     * Dönem bazlı oran değeri iç DTO'su.
     *
     * <p>Yıl ve ay bilgisiyle birlikte bir sayısal değer taşır.
     * F/K bandı, net borç trendi gibi zaman serisi verileri için kullanılır.</p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DonemOranDto {

        /** Bilanço yılı (ör: 2024). */
        private Integer yil;

        /** Bilanço ayı (3, 6, 9, 12). */
        private Integer ay;

        /** Dönem değeri (F/K oranı, net borç tutarı vb.). */
        private Double deger;
    }
}
