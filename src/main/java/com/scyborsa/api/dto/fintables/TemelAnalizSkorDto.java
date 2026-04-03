package com.scyborsa.api.dto.fintables;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Temel analiz skor DTO'su.
 *
 * <p>Altman Z-Score, Piotroski F-Score ve Graham Sayısı hesaplama
 * sonuçlarını taşır. Her bir skor bileşeni bağımsız olarak hesaplanır;
 * hesaplanamayan bileşenler {@code null} döner (graceful degradation).</p>
 *
 * <h3>Altman Z-Score Yorumlama:</h3>
 * <ul>
 *   <li>{@code > 2.99} — GUVENLI (iflas riski düşük)</li>
 *   <li>{@code 1.81 - 2.99} — GRI (belirsiz bölge)</li>
 *   <li>{@code < 1.81} — TEHLIKE (iflas riski yüksek)</li>
 * </ul>
 *
 * <h3>Piotroski F-Score Yorumlama:</h3>
 * <ul>
 *   <li>{@code 7-9} — GUCLU (finansal sağlık iyi)</li>
 *   <li>{@code 4-6} — ORTA (karma sinyaller)</li>
 *   <li>{@code 0-3} — ZAYIF (finansal sağlık zayıf)</li>
 * </ul>
 *
 * <h3>Graham Sayısı:</h3>
 * <p>Benjamin Graham'ın içsel değer formülü. {@code grahamMarji} pozitifse
 * hisse ucuz, negatifse pahalı olarak yorumlanır.</p>
 *
 * @see com.scyborsa.api.service.enrichment.TemelAnalizSkorService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemelAnalizSkorDto {

    /** Hisse senedi kodu (ör: "GARAN"). */
    private String hisseSenediKodu;

    /** Altman Z-Score değeri. {@code > 2.99} güvenli, {@code < 1.81} tehlike. */
    private Double altmanZScore;

    /** Altman Z-Score bölgesi: "GUVENLI", "GRI" veya "TEHLIKE". */
    private String altmanZone;

    /** Piotroski F-Score (0-9 arası, 9 = en güçlü). */
    private Integer piotroskiFScore;

    /** Piotroski F-Score bölgesi: "GUCLU" (7-9), "ORTA" (4-6) veya "ZAYIF" (0-3). */
    private String piotroskiZone;

    /** Graham içsel değer (intrinsic value). */
    private Double grahamSayisi;

    /** Graham güvenlik marjı yüzdesi: (grahamSayısı - mevcutFiyat) / mevcutFiyat * 100. */
    private Double grahamMarji;
}
