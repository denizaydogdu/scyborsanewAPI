package com.scyborsa.api.dto.fintables;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * KAP MCP haber DTO'su.
 *
 * <p>Fintables MCP {@code dokumanlarda_ara} tool'undan dönen
 * KAP bildirim verilerini temsil eder. Chunk ID'leri ile
 * detay içeriğe erişim sağlanır.</p>
 *
 * @see com.scyborsa.api.service.kap.KapMcpService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KapMcpHaberDto {

    /** Haber başlığı. */
    private String baslik;

    /** Haber özeti. */
    private String ozet;

    /** Haber tarihi (yyyy-MM-dd formatında). */
    private String tarih;

    /** Bildirim tipi (ODA/FR/CA/DKB/FON/DUY/DG). */
    private String bildirimTipi;

    /** İlgili hisse senedi kodu (ör: "GARAN"). */
    private String hisseSenediKodu;

    /** Doküman chunk ID'leri (detay içerik için). */
    private List<String> chunkIds;
}
