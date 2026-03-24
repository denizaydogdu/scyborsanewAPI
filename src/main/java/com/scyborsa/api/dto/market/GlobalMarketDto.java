package com.scyborsa.api.dto.market;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Global piyasa verilerini tasiyan DTO.
 *
 * <p>Emtia, doviz, kripto ve uluslararasi endeks verilerini icerir.
 * TradingView Scanner API {@code /global/scan} endpoint'inden elde edilir.</p>
 *
 * @see com.scyborsa.api.service.market.GlobalMarketService
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GlobalMarketDto {

    /** Sembol kodu (orn. "GOLD", "USDTRY", "BTCUSD"). */
    private String symbol;

    /** Enstruman adi (TradingView description alani, Turkce). */
    private String name;

    /** Son islem fiyati (close). Null ise veri yok. */
    private Double lastPrice;

    /** Gunluk degisim yuzdesi. Null ise veri yok. */
    private Double dailyChange;

    /** Kategori: "EMTIA", "DOVIZ", "KRIPTO" veya "ENDEKS". */
    private String category;
}
