package com.scyborsa.api.dto.fintables;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Fintables topic-feed paginated response DTO'su.
 *
 * <p>{@code /topic-feed/?page_size=15&for_everyone=1&only_pro=1} endpoint'inden
 * donen sayfalanmis response yapisini temsil eder.</p>
 *
 * @see FintablesTopicFeedItemDto
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FintablesTopicFeedResponseDto {

    /** Sonraki sayfa URL'i (null ise son sayfa). */
    private String next;

    /** Onceki sayfa URL'i (null ise ilk sayfa). */
    private String previous;

    /** Topic feed item listesi. */
    private List<FintablesTopicFeedItemDto> results;
}
