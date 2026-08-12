package com.union.control.scheduled;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.service.DelegatedSessionService;
import com.union.control.service.NonStreamRunService;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private final NonStreamRunService nonStream;
    private final DelegatedSessionService delegatedSessions;
    private final ObjectMapper json;
    private final ThreadPoolExecutor workers;
    private final boolean enabled;
    private final int scanBatchSize;
    private final int maxRunSeconds;
    private final Set<Long> submitted = Collections.newSetFromMap(
            new ConcurrentHashMap<Long, Boolean>());

    public ScheduledTaskScheduler(
            ScheduledTaskService service,
            NonStreamRunService nonStream,
            DelegatedSessionService delegatedSessions,
            ObjectMapper json,
            @Value("${agent.scheduled-enabled:false}") boolean enabled,
            @Value("${agent.scheduled-worker-threads:2}") int workerThreads,
            @Value("${agent.scheduled-worker-queue:32}") int workerQueue,
            @Value("${agent.scheduled-scan-batch-size:20}") int scanBatchSize,
            @Value("${agent.scheduled-max-run-seconds:930}") int maxRunSeconds) {
        this.service = service;
        this.nonStream = nonStream;
        this.delegatedSessions = delegatedSessions;
        this.json = json;
        this.enabled = enabled;
        if (enabled && !delegatedSessions.isConfigured())
            throw new IllegalStateException(
                    "SCHEDULED_TASK_ENABLED=true 时必须配置受信任的 delegated session");
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
                            service.completeFromProxy(runId, execute(runId));
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

    private Map<String, Object> execute(long runId) {
        Map<String, Object> context = service.executionContext(runId);
        String owner = required(context, "userId");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("scheduledTaskId", context.get("taskId"));
        input.put("scheduledRunId", context.get("runId"));
        input.put("scheduledAt", context.get("scheduledAt"));
        input.put("timezone", context.get("timezone"));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("question", required(context, "prompt"));
        request.put("input", input);
        try {
            ResponseEntity<byte[]> response = nonStream.run(
                    delegatedSessions.cookieForOwner(owner), json.writeValueAsBytes(request));
            if (!response.getStatusCode().is2xxSuccessful())
                throw new IllegalStateException("Agent sync 返回非成功状态");
            byte[] body = response.getBody();
            if (body == null || body.length == 0 || body.length > 4000000)
                throw new IllegalStateException("Agent sync 返回无效结果");
            Map<String, Object> result = json.readValue(
                    body, new TypeReference<Map<String, Object>>() {});
            required(result, "content");
            return result;
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Agent sync 请求或响应无效", error);
        }
    }

    private static String required(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty())
            throw new IllegalStateException("Scheduled run context 缺少 " + key);
        return (String) value;
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
