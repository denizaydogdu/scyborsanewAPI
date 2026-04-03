package com.scyborsa.api.dto.fintables;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Portföy temel veri zenginleştirme DTO'su.
 *
 * <p>Bir hissenin temel analiz verilerini (F/K, PD/DD, ROE, temettü,
 * analist konsensüs, hedef fiyat, sinyal sayısı) taşır.
 * Portföy sayfasında hisse bazlı temel veri gösterimi için kullanılır.</p>
 *
 * @see com.scyborsa.api.service.enrichment.PortfoyZenginlestirmeService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortfoyTemelVeriDto {

    /** Hisse senedi kodu (ör: "GARAN"). */
    private String hisseSenediKodu;

    /** Fiyat/Kazanç oranı. */
    private Double fk;

    /** Piyasa Değeri / Defter Değeri oranı. */
    private Double pdDd;

    /** Özsermaye Kârlılığı (%). */
    private Double roe;

    /** Temettü tarihi (ör: "2025-05-15"). */
    private String temettuTarihi;

    /** Temettü verimi (%). */
    private Double temettuVerimi;

    /** Analist konsensüs tavsiyesi: "AL", "TUT" veya "SAT". */
    private String analistKonsensus;

    /** Analist hedef fiyat (TL). */
    private Double hedefFiyat;

    /** Temel analiz sinyal sayısı (pozitif + negatif toplam). */
    private Integer sinyalSayisi;
}
