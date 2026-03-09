package com.scyborsa.api.dto;

/**
 * Tek bir screener scan body tanımı.
 *
 * <p>JSON resource dosyasından yüklenen tarama adı ve body içeriğini taşır.
 * {@link com.scyborsa.api.config.ScreenerScanBodyRegistry} tarafından üretilir.</p>
 *
 * @param name tarama stratejisinin adı (ör. "BIST_VEGAS")
 * @param body TradingView Screener API'sine gönderilecek JSON body
 */
public record ScanBodyDefinition(String name, String body) {
}
