package com.scyborsa.api.dto.bilanco;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Bilanco tablo konteynir DTO'su.
 *
 * <p>Bir finansal raporun tablo tipini ve tablo kalemlerini icerir.
 * Tablo tipi "Bilanço", "Gelir Tablosu" veya "Nakit Akım Tablosu" olabilir.</p>
 *
 * @see BilancoTableItemDto
 * @see BilancoDataDto
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BilancoTablesDto {

    /** Tablo tipi adi (orn. "Bilanço", "Gelir Tablosu"). */
    private String tableTypeName;

    /** Tablo kalemleri listesi. */
    private List<BilancoTableItemDto> tableItems;
}
