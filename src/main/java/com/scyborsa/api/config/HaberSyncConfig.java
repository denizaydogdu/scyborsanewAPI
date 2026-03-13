package com.scyborsa.api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Haber senkronizasyon yapilandirmasi.
 *
 * <p>{@code application.yml}'de {@code haber.sync.*} prefix'i ile yonetilir.
 * Sync araligi, fetch limitleri, timeout ve temizlik parametrelerini icerir.</p>
 *
 * <pre>
 * haber:
 *   sync:
 *     enabled: true
 *     max-fetch-per-cycle: 5
 *     fetch-timeout: 10000
 *     max-stored: 100
 *     fetch-delay: 1000
 * </pre>
 *
 * @see com.scyborsa.api.service.job.HaberSyncJob
 * @see com.scyborsa.api.service.kap.HaberDetailService
 */
@Configuration
@ConfigurationProperties(prefix = "haber.sync")
@Data
public class HaberSyncConfig {

    /** Sync aktif mi? */
    private boolean enabled = true;

    /** Her cycle'da maksimum detay fetch sayisi. */
    private int maxFetchPerCycle = 5;

    /** Jsoup/HTTP fetch timeout (ms). */
    private int fetchTimeout = 10000;

    /** DB'de saklanacak maksimum haber sayisi. */
    private int maxStored = 100;

    /** Fetch'ler arasi bekleme (ms) — rate limiting. */
    private long fetchDelay = 1000;
}
