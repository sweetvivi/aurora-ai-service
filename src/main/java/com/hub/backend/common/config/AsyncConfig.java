package com.hub.backend.common.config;

import com.hub.backend.common.config.properties.PptProperties;
import com.hub.backend.common.config.properties.QueueProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Dedicated thread pools for work that must not run on a request thread.
 *
 * <p>Streaming must not consume the servlet container request threads, otherwise a handful of
 * long conversations can starve every other endpoint.</p>
 *
 * <p>Video generation gets its own, much smaller pool: submitting a job is a single upstream call,
 * after which the work is driven by the scheduled poller. A large pool here would only let a burst
 * of submissions hammer the gateway.</p>
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "chatStreamExecutor")
    public ThreadPoolTaskExecutor chatStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("chat-stream-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }

    @Bean(name = "pptJobExecutor")
    public ThreadPoolTaskExecutor pptJobExecutor(PptProperties pptProperties) {
        int concurrency = Math.max(1, pptProperties.getPptMaster().getJobConcurrency());
        int queue = Math.max(1, pptProperties.getPptMaster().getJobQueueSize());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix("ppt-job-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 论文全文生成（SSE）专用池。
     *
     * <p>这类请求要一直挂着连接写到写完（通常 1-3 分钟），以前是每请求裸 `new Thread()`：
     * 没有上限、没有拒绝策略，几百个并发就能把 JVM 线程开穿，容器关机时也没人收敛。
     * 队列有界，满了直接拒绝，控制器据此返回 503，而不是让请求无限排队。</p>
     */
    @Bean(name = "thesisFullTextExecutor")
    public ThreadPoolTaskExecutor thesisFullTextExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("thesis-fulltext-");
        // 队列满时抛 RejectedExecutionException：由控制器翻成 503 + 中文提示，不静默排队。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 关机时先让正在写的章收尾，而不是把连接直接掐断。
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean(name = "videoTaskExecutor")
    public ThreadPoolTaskExecutor videoTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("video-task-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        // A submit call is bounded by app.llm.video-timeout; do not cut it short on shutdown.
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 任务队列的处理器线程池。
     *
     * <p>队列里的活儿是「删文件、发通知、补偿」这类副作用，单个几毫秒到几秒，所以线程数很小。
     * 队列有界 + AbortPolicy：满了就让派发方把消息还回队列，而不是在这里无限排队 ——
     * 一条消息躺在执行队列里是不能被别的实例接手的，越堆越久只会让延迟不可控。</p>
     */
    @Bean(name = "taskQueueExecutor")
    public ThreadPoolTaskExecutor taskQueueExecutor(QueueProperties queueProperties) {
        int threads = Math.max(1, queueProperties.getWorkerThreads());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(Math.max(1, queueProperties.getWorkerQueueSize()));
        executor.setThreadNamePrefix("task-queue-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 关机时让在处理的副作用收尾，别把「删了一半」当成完成。
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
