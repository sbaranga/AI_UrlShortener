package com.example.urlshortener.orchestration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrchestrationConfig {

    /**
     * Pool the forked channels run on. It must have room for every node in the widest stage, or
     * the "parallel" channels would queue behind each other and the fork would be sequential.
     */
    @Bean(destroyMethod = "shutdownNow")
    public Executor orchestrationExecutor(OrchestrationProperties properties) {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "orchestration-" + counter.incrementAndGet());
            // Daemon so a stuck module can never keep the JVM alive on shutdown.
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(properties.getWorkerThreads(), factory);
    }
}
