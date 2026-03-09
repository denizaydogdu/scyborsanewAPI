package com.scyborsa.api.service.enrichment;

import com.scyborsa.api.dto.enrichment.KurumsalGucDTO;

/**
 * Kurumsal Guc Skoru (KGS) hesaplama servisi.
 *
 * <p>Henuz implement edilmedi. Implement edildiginde
 * {@link com.scyborsa.api.service.telegram.TelegramMessageFormatter}
 * otomatik olarak kullanmaya baslayacaktir.</p>
 */
public interface KurumsalGucService {

    /**
     * Verilen hisse icin KGS hesaplar.
     *
     * @param stockName hisse kodu (orn. "THYAO")
     * @return KGS sonucu
     */
    KurumsalGucDTO calculateScore(String stockName);
}
