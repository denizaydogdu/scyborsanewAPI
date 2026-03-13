package com.scyborsa.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Scheduled task thread pool yapılandırması.
 *
 * <p>Spring'in default single-threaded {@code TaskScheduler}'ı yerine
 * multi-threaded pool kullanır. Bu sayede blocking I/O yapan job'lar
 * (HTTP çağrısı, Thread.sleep) diğer job'ları bloklamaz.</p>
 *
 * <p>Pool size: 15 thread — mevcut 11 scheduled job (7 sync + 4 @Async)
 * ve gelecek job'lar icin yeterli.</p>
 *
 * @see org.springframework.scheduling.annotation.EnableScheduling
 */
@Configuration
public class SchedulingConfig implements SchedulingConfigurer {

    private static final int POOL_SIZE = 15;

    /**
     * Scheduled task executor'ı yapılandırır.
     *
     * @param taskRegistrar Spring task registrar
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(POOL_SIZE);
        scheduler.setThreadNamePrefix("scy-sched-");
        scheduler.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        taskRegistrar.setTaskScheduler(scheduler);
    }
}
