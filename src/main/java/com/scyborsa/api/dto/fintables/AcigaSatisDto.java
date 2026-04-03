package com.scyborsa.api.dto.fintables;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Günlük açığa satış istatistikleri DTO'su.
 *
 * <p>Fintables MCP {@code gunluk_aciga_satis_istatistikleri} tablosundaki
 * verileri temsil eder. Her satır bir hissenin o günkü açığa satış
 * bilgilerini içerir.</p>
 *
 * @see com.scyborsa.api.service.enrichment.AcigaSatisService
 * @see com.scyborsa.api.service.enrichment.AcigaSatisSyncJob
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AcigaSatisDto {

    /** Hisse senedi kodu (ör: "GARAN"). */
    private String hisseSenediKodu;

    /** Veri tarihi (yyyy-MM-dd formatında). */
    private String tarih;

    /** Ortalama açığa satış fiyatı (TL). */
    private Double ortalamaAcigaSatisFiyati;

    /** En yüksek açığa satış fiyatı (TL). */
    private Double enYuksekAcigaSatisFiyati;

    /** En düşük açığa satış fiyatı (TL). */
    private Double enDusukAcigaSatisFiyati;

    /** Açığa satış hacmi (TL). */
    private Double acigaSatisHacmiTl;

    /** Toplam işlem hacmi (TL). */
    private Double toplamIslemHacmiTl;

    /** Açığa satış lot adedi. */
    private Long acigaSatisLotu;

    /** Toplam işlem hacmi (lot). */
    private Long toplamIslemHacmiLot;
}
