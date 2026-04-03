package com.scyborsa.api.dto.fintables;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Analist hedef fiyat ve tavsiye DTO'su.
 *
 * <p>Fintables MCP {@code hisse_senedi_araci_kurum_hedef_fiyatlari} tablosundaki
 * verileri temsil eder. Her satır bir aracı kurumun bir hisseye verdiği
 * hedef fiyat ve tavsiye bilgisini içerir.</p>
 *
 * @see com.scyborsa.api.service.enrichment.HedefFiyatService
 * @see com.scyborsa.api.service.enrichment.HedefFiyatSyncJob
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HedefFiyatDto {

    /** Hedef fiyat kaydının benzersiz kimliği. */
    private Integer hedefFiyatId;

    /** Hisse senedi kodu (ör: "GARAN"). */
    private String hisseSenediKodu;

    /** Aracı kurum kodu (ör: "AKY"). */
    private String araciKurumKodu;

    /** Yayın tarihi (yyyy-MM-dd formatında). */
    private String yayinTarihi;

    /** Tavsiye yönü (ör: "AL", "TUT", "SAT"). */
    private String tavsiye;

    /** Hedef fiyat (TL). */
    private Double hedefFiyat;

    /** Hedef fiyat tarihi (yyyy-MM-dd formatında). */
    private String hedefFiyatTarihi;

    /** Model portföyde olup olmadığı. */
    private Boolean modelPortfoyde;

    /** Aracı kurum rapor doküman kimliği. */
    private String araciKurumRaporDokumanId;
}
