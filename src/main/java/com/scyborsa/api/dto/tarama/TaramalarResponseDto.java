package com.scyborsa.api.dto.tarama;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Taramalar sayfası API yanıt DTO'su.
 *
 * <p>Tarama sinyalleri listesi, özet istatistikler, filtre için tarama adları
 * ve toplam kart sayısını içerir.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaramalarResponseDto {

    /** Tarama sinyalleri listesi (gruplu veya tekil). */
    private List<TaramaDto> taramalar;

    /** Tarama özet istatistikleri. */
    private TaramaOzetDto ozet;

    /** Filtre dropdown için benzersiz tarama stratejisi adları. */
    private List<String> screenerNames;

    /** Görüntülenen kart sayısı (gruplama sonrası). Ham sinyal sayısı için {@link TaramaOzetDto#toplamSinyal} kullanılmalı. */
    private int toplamKart;

    /** Hisse bazli gruplanmis sinyal listesi. {@code groupByStock=true} ise dolu, degilse {@code null}. */
    private List<StockGroupDto> stockGroups;
}
