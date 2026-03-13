package com.scyborsa.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Screener taramalarının paralel çalıştırılması için thread pool konfigürasyonu.
 *
 * <p>Her screener türü içindeki scan body'ler bu executor üzerinden paralel çalıştırılır.
 * CallerRunsPolicy ile queue dolduğunda backpressure sağlanır.</p>
 *
 * @see com.scyborsa.api.service.screener.ScreenerExecutionService
 */
@Configuration
public class ScreenerSchedulerConfig {

    /**
     * Screener taramaları için thread pool executor bean'i oluşturur.
     *
     * @return yapılandırılmış executor
     */
    @Bean("screenerTaskExecutor")
    public ThreadPoolTaskExecutor screenerTaskExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("screener-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        return executor;
    }
}
