package com.scyborsa.api.dto.bilanco;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Bilanco tablo satiri DTO'su (recursive/agac yapisi).
 *
 * <p>Bir bilanco tablosundaki tek bir kalemi temsil eder.
 * Alt kalemler {@code tableItems} listesi ile recursive olarak tutulur.</p>
 *
 * <p>{@code isAbstract} alani JSON'da "abstract" key'i ile gelir
 * ve bu kalemin bir gruplama basligi mi yoksa deger iceren bir kalem mi oldugunu belirtir.</p>
 *
 * @see BilancoTablesDto
 * @see BilancoValueDto
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BilancoTableItemDto {

    /** Kalem adi (orn. "Donan Varliklar"). */
    private String name;

    /** Kalem aciklamasi. */
    private String description;

    /** Kalemin parasal degeri. */
    private BilancoValueDto value;

    /** Alt kalemler (recursive agac yapisi). */
    private List<BilancoTableItemDto> tableItems;

    /** Kalemin soyut/gruplama basligi olup olmadigini belirtir. JSON: "abstract". */
    @JsonProperty("abstract")
    private Boolean isAbstract;

    /** Sayisal deger (hesaplama icin). */
    private Double numericValue;

    /** Tercih edilen etiket (orn. "totalLabel", "periodStartLabel"). */
    private String preferredLabel;
}
