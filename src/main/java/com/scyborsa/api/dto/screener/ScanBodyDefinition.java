package com.scyborsa.api.dto.screener;

/**
 * Tek bir screener scan body tanimi.
 *
 * <p>JSON resource dosyasindan yuklenen tarama adi ve body icerigini tasir.
 * {@link com.scyborsa.api.config.ScreenerScanBodyRegistry} tarafindan uretilir.</p>
 *
 * @param name tarama stratejisinin adi (or. "BIST_VEGAS")
 * @param body TradingView Screener API'sine gonderilecek JSON body
 */
public record ScanBodyDefinition(String name, String body) {
}
