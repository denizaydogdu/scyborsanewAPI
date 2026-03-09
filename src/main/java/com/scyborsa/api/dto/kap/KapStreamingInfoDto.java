package com.scyborsa.api.dto.kap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * KAP haber streaming kanal bilgisi DTO'su.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class KapStreamingInfoDto {

    /** Streaming kanal adı. */
    private String channel;
}
