package com.scyborsa.api.dto.fintables;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Halka arz verileri DTO'su.
 *
 * <p>Fintables MCP {@code halka_arzlar} tablosundaki verileri temsil eder.
 * Her satır bir halka arz sürecinin detaylarını içerir.</p>
 *
 * @see com.scyborsa.api.service.enrichment.HalkaArzService
 * @see com.scyborsa.api.service.enrichment.HalkaArzSyncJob
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HalkaArzDto {

    /** Hisse senedi kodu (ör: "GARAN"). */
    private String hisseSenediKodu;

    /** Halka arz başlığı. */
    private String baslik;

    /** Talep toplama başlangıç tarihi (yyyy-MM-dd formatında). */
    private String talepToplamaBaslangicTarihi;

    /** Talep toplama bitiş tarihi (yyyy-MM-dd formatında). */
    private String talepToplamaBitisTarihi;

    /** Borsada ilk işlem tarihi (yyyy-MM-dd formatında). */
    private String ilkIslemTarihi;

    /** Halka arz fiyatı (TL). */
    private Double halkaArzFiyati;

    /** Düzeltilmiş halka arz fiyatı (TL). */
    private Double duzeltilmisHalkaArzFiyati;

    /** Halka arz pay adedi. */
    private Long payAdedi;

    /** Ek pay adedi (ek satış opsiyonu). */
    private Long ekPayAdedi;

    /** Aracı kurum adı. */
    private String araciKurum;

    /** Katılım endeksi uygunluk durumu. */
    private Boolean katilimEndeksiUygunMu;

    /** Katılımcı sayısı. */
    private Integer katilimciSayisi;

    /** Durum kodu (ör: "tamamlandi", "devam_ediyor"). */
    private String durumKodu;

    /** Yıllıklandırılmış kâr oranı (%). */
    private Double yilliklandirilmisKar;

    /** Halka arz sonrası ödenmiş sermaye. */
    private Double halkaArzSonrasiOdenmisSermaye;

    /** İskonto oranı (%). */
    private Double iskontoOrani;

    /** Net kâr (TL). */
    private Double netKar;

    /** FAVÖK (TL). */
    private Double favok;

    /** Net borç (TL). */
    private Double netBorc;
}
