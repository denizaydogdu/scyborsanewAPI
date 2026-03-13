package com.scyborsa.api.dto.enrichment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * Fintables Orderbook API ham response DTO'su.
 *
 * <p>{@code /mobile/orderbook/transactions/} endpoint'inden dönen
 * paginated işlem verisi.</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FintablesOrderbookResponseDto {

    /** Sonraki sayfa cursor URL'i. */
    private String next;

    /** Önceki sayfa cursor URL'i. */
    private String previous;

    /** İşlem listesi. */
    private List<OrderbookItem> results;

    /**
     * Tek bir emir defteri işlemi.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OrderbookItem {

        /** Fiyat. */
        private double p;

        /** Lot (adet, double → int dönüştürülecek). */
        private double s;

        /** İşlem yönü: "B" (Alış) / "S" (Satış). */
        private String a;

        /** Alıcı kurum kodu. */
        private String bb;

        /** Satıcı kurum kodu. */
        private String sb;

        /** Transaction ID. */
        private long i;

        /** Emir tipi (ör: "N" Normal). */
        private String o;

        /** Unix epoch seconds. */
        private long t;
    }
}
