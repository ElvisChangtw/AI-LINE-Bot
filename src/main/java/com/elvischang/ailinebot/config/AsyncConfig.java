package com.elvischang.ailinebot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Bean(name = "jobExecutor")
    public ThreadPoolTaskExecutor jobExecutor() {
        return executor("job-", 2, 50);
    }

    @Bean(name = "mediaExecutor")
    public ThreadPoolTaskExecutor mediaExecutor() {
        return executor("media-", 1, 100);
    }

    private static ThreadPoolTaskExecutor executor(String prefix, int threads, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(prefix);
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queueCapacity);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler((task, pool) ->
                log.warn("executor {} queue full; recovery sweep will pick the task up", prefix));
        return executor;
    }
}
