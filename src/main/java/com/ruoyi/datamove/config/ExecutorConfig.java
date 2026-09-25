package com.ruoyi.datamove.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步执行器配置 - 用于 sync_log 的异步落库
 */
@Configuration
@EnableAsync
public class ExecutorConfig {

    @Bean("syncExecutor")
    public Executor syncExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(20);
        exec.setQueueCapacity(500);
        exec.setKeepAliveSeconds(60);
        exec.setThreadNamePrefix("sync-log-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.initialize();
        return exec;
    }

    /**
     * 任务调度线程池 - CRON 定时触发用 (TaskTriggerScheduler)
     * 线程只负责「到点点一下启动」, 同步本体在各引擎线程里跑, 2 个线程足够
     */
    @Bean
    public ThreadPoolTaskScheduler triggerScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(2);
        s.setThreadNamePrefix("task-trigger-");
        s.setRemoveOnCancelPolicy(true);
        s.initialize();
        return s;
    }
}
