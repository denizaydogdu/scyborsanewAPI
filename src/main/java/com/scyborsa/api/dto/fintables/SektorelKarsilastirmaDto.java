package com.scyborsa.api.dto.fintables;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Sektörel oran karşılaştırma DTO'su.
 *
 * <p>Bir hissenin finansal oranlarını (F/K, PD/DD, ROE vb.) aynı sektördeki
 * diğer hisselerle karşılaştırarak sektörel konumunu belirler.</p>
 *
 * <h3>Pozisyon Yorumlama:</h3>
 * <ul>
 *   <li>{@code "UCUZ"} — Şirketin oranı sektör medyanının %70'inden düşük</li>
 *   <li>{@code "ORTADA"} — Şirketin oranı medyanın %70-%130 aralığında</li>
 *   <li>{@code "PAHALI"} — Şirketin oranı sektör medyanının %130'undan yüksek</li>
 * </ul>
 *
 * <p>Her bir oran bağımsız karşılaştırılır. Yeterli sektör verisi yoksa
 * ilgili oran sonucu {@code null} kalır (graceful degradation).</p>
 *
 * @see com.scyborsa.api.service.enrichment.SektorelKarsilastirmaService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SektorelKarsilastirmaDto {

    /** Hisse senedi kodu (ör: "GARAN"). */
    private String hisseSenediKodu;

    /** Şirketin ait olduğu sektör adı. */
    private String sektor;

    /** Şirketin finansal oranları. Anahtar: oran adı (ör: "F/K"), değer: oran değeri. */
    private Map<String, Double> sirketOranlari;

    /** Sektör ortalaması. Anahtar: oran adı, değer: sektör ortalama değeri. */
    private Map<String, Double> sektorOrtalama;

    /** Sektör medyanı. Anahtar: oran adı, değer: sektör medyan değeri. */
    private Map<String, Double> sektorMedian;

    /** Sektörel pozisyon. Anahtar: oran adı, değer: "UCUZ" / "ORTADA" / "PAHALI". */
    private Map<String, String> pozisyon;
}
