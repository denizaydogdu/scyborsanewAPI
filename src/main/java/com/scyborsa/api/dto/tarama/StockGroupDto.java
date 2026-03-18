package com.scyborsa.api.dto.tarama;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Hisse bazli gruplanmis tarama sinyallerini tasiyan DTO.
 *
 * <p>{@code groupByStock=true} parametresi ile taramalar sayfasinda
 * hisseler benzersiz olarak gruplanir. Her hisse icin sinyal sayisi,
 * ilk/son fiyat ve gun sonu degisim ozeti saglanir.</p>
 *
 * @see TaramalarResponseDto
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockGroupDto {

    /** Hisse borsa kodu (orn. "GARAN"). */
    private String stockName;

    /** Bu hisseye ait toplam sinyal sayisi. */
    private int sinyalSayisi;

    /** Ilk sinyaldeki fiyat (zamana gore). */
    private Double ilkFiyat;

    /** Son sinyaldeki fiyat (zamana gore). */
    private Double sonFiyat;

    /** Son sinyalin gun sonu degisim yuzdesi. */
    private Double gunSonuDegisim;

    /** Bu hisseye ait tum sinyallerin detayli listesi. */
    private List<TaramaDto> sinyaller;
}
