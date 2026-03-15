package com.scyborsa.api.dto.market;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Para akisi toplu yanit DTO'su.
 * <p>
 * En yuksek ciro ile para girisi ve para cikisi yapan hisseleri bir arada dondurur.
 * REST endpoint'i uzerinden client'a iletilir.
 * </p>
 *
 * @see MoneyFlowStockDto
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoneyFlowResponse {

    /** Para girisi yapan hisseler (pozitif degisim, ciroya gore azalan sirada). */
    private List<MoneyFlowStockDto> inflow;

    /** Para cikisi yapan hisseler (negatif degisim, ciroya gore azalan sirada). */
    private List<MoneyFlowStockDto> outflow;
}
