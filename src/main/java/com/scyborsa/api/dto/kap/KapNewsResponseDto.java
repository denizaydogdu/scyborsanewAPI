package com.scyborsa.api.dto.kap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * KAP canlı haber API response DTO'su.
 *
 * <p>TradingView news-mediator API'sinden dönen üst düzey response yapısını temsil eder.</p>
 *
 * @see KapNewsItemDto
 * @see KapStreamingInfoDto
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class KapNewsResponseDto {

    /** Haber öğeleri listesi. */
    private List<KapNewsItemDto> items;

    /** Streaming bağlantı bilgisi. */
    private KapStreamingInfoDto streaming;
}
