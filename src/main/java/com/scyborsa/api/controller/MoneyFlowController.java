package com.scyborsa.api.controller;

import com.scyborsa.api.dto.market.MoneyFlowResponse;
import com.scyborsa.api.service.market.MoneyFlowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Para akisi (money flow) verilerini sunan REST controller.
 *
 * <p>En yuksek ciro ile para girisi ve cikisi yapan BIST hisselerini dondurur.
 * Veriler TradingView Scanner API'sinden cekilir ve adaptif cache ile saklanir.</p>
 *
 * <p>Temel endpoint: {@code /api/v1/money-flow}</p>
 *
 * @see MoneyFlowService
 * @see MoneyFlowResponse
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/money-flow")
@RequiredArgsConstructor
public class MoneyFlowController {

    /** Para akisi servis katmani. */
    private final MoneyFlowService moneyFlowService;

    /**
     * Para girisi ve cikisi yapan hisseleri dondurur.
     *
     * <p>HTTP GET {@code /api/v1/money-flow}</p>
     *
     * <p>Pozitif degisimli en yuksek cirolu 5 hisse (inflow) ve negatif degisimli
     * en yuksek cirolu 5 hisse (outflow) olarak gruplanmis sonuc doner.</p>
     *
     * @return para girisi ve cikisi hisse listelerini iceren {@link MoneyFlowResponse}
     */
    @GetMapping
    public ResponseEntity<MoneyFlowResponse> getMoneyFlow() {
        return ResponseEntity.ok(moneyFlowService.getMoneyFlow());
    }
}
