package com.scyborsa.api.dto.enrichment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * AKD (Aracı Kurum Dağılımı) zenginleştirilmiş response DTO'su.
 * Fintables ham verisinin DB ile eşleştirilip alıcı/satıcı/toplam olarak ayrıştırılmış hali.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AkdResponseDto {

    /** Net alıcı kurumlar listesi (net.size > 0, büyükten küçüğe sıralı). */
    private List<AkdBrokerDto> alicilar;

    /** Net satıcı kurumlar listesi (net.size < 0, büyükten küçüğe sıralı). */
    private List<AkdBrokerDto> saticilar;

    /** Tüm kurumlar toplam işlem hacmine göre (büyükten küçüğe sıralı). */
    private List<AkdBrokerDto> toplam;

    /** Verinin ait olduğu tarih (yyyy-MM-dd formatında). */
    private String dataDate;

    /** Formatlanmış tarih (ör: "11 Mart 2026"). */
    private String formattedDataDate;
}
