package com.union.control.scheduled;

import com.union.control.service.AgentProxyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ScheduledTaskScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(ScheduledTaskScheduler.class);
    private final ScheduledTaskService service;
    private final AgentProxyService proxy;
    private final ThreadPoolExecutor workers;
    private final boolean enabled;
    private final int scanBatchSize;
    private final int maxRunSeconds;
    private final Set<Long> submitted = Collections.newSetFromMap(
            new ConcurrentHashMap<Long, Boolean>());

    public ScheduledTaskScheduler(
            ScheduledTaskService service,
            AgentProxyService proxy,
            @Value("${agent.scheduled-enabled:false}") boolean enabled,
            @Value("${agent.scheduled-worker-threads:2}") int workerThreads,
            @Value("${agent.scheduled-worker-queue:32}") int workerQueue,
            @Value("${agent.scheduled-scan-batch-size:20}") int scanBatchSize,
            @Value("${agent.scheduled-max-run-seconds:930}") int maxRunSeconds) {
        this.service = service;
        this.proxy = proxy;
        this.enabled = enabled;
        if (enabled && !proxy.isScheduledTaskConfigured())
            throw new IllegalStateException(
                    "SCHEDULED_TASK_ENABLED=true 时必须配置 SCHEDULED_TASK_TOKEN");
        this.scanBatchSize = Math.max(1, Math.min(scanBatchSize, 100));
        this.maxRunSeconds = Math.max(60, maxRunSeconds);
        int threads = Math.max(1, Math.min(workerThreads, 16));
        int queue = Math.max(1, Math.min(workerQueue, 1000));
        this.workers = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(queue), new NamedThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Scheduled(
            initialDelayString = "${agent.scheduled-initial-delay-ms:1000}",
            fixedDelayString = "${agent.scheduled-scan-interval-ms:5000}")
    public void scan() {
        if (!enabled) return;
        service.failStaleRuns(maxRunSeconds);
        Set<Long> ready = new LinkedHashSet<>(service.pendingRunIds(scanBatchSize));
        while (ready.size() < scanBatchSize) {
            Long runId = service.claimDueRun();
            if (runId == null) break;
            ready.add(runId);
        }
        for (Long runId : ready) submit(runId);
    }

    private void submit(final long runId) {
        if (!submitted.add(runId)) return;
        try {
            workers.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!service.beginRun(runId)) return;
                        try {
                            service.completeFromProxy(runId, proxy.executeScheduledTask(runId));
                        } catch (RuntimeException error) {
                            LOG.warn("Scheduled run failed run_id={} error_type={}",
                                    runId, error.getClass().getSimpleName());
                            try {
                                service.failRun(runId, "scheduled_run_failed", "定时任务执行失败");
                            } catch (RuntimeException finishError) {
                                LOG.error("Scheduled run failure persistence failed run_id={}", runId);
                            }
                        }
                    } finally {
                        submitted.remove(runId);
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            submitted.remove(runId);
            LOG.warn("Scheduled worker queue full run_id={}", runId);
        }
    }

    @PreDestroy
    public void close() {
        workers.shutdownNow();
    }

    private static class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                    "scheduled-task-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
