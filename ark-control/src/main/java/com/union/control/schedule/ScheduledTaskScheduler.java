package com.union.control.schedule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.service.AgentProxyService;
import com.union.control.service.AgentResponse;
import com.union.control.service.ScheduledTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Claims and executes due tasks. Control is trusted and deliberately has no permission check. */
@Component
public class ScheduledTaskScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(ScheduledTaskScheduler.class);
    private static final int SCAN_BATCH_SIZE = 20;
    private static final int WORKER_QUEUE_SIZE = 32;
    private final ScheduledTaskService service;
    private final AgentProxyService proxy;
    private final ObjectMapper json;
    private final ThreadPoolExecutor workers;
    private final boolean enabled;
    private final int maxRunSeconds;
    private final int tokenTtlSeconds;
    private final Set<Long> submitted = Collections.newSetFromMap(
            new ConcurrentHashMap<Long, Boolean>());

    public ScheduledTaskScheduler(
            ScheduledTaskService service,
            AgentProxyService proxy,
            ObjectMapper json,
            @Value("${agent.scheduled-enabled:false}") boolean enabled,
            @Value("${agent.scheduled-worker-threads:2}") int workerThreads,
            @Value("${agent.scheduled-max-run-seconds:930}") int maxRunSeconds,
            @Value("${agent.scheduled-token-ttl-seconds:960}") int tokenTtlSeconds) {
        if (tokenTtlSeconds <= maxRunSeconds || tokenTtlSeconds > 3600)
            throw new IllegalArgumentException(
                    "scheduled token TTL must exceed max run time and be <= 3600");
        this.service = service;
        this.proxy = proxy;
        this.json = json;
        this.enabled = enabled;
        this.maxRunSeconds = Math.max(60, maxRunSeconds);
        this.tokenTtlSeconds = tokenTtlSeconds;
        int threads = Math.max(1, Math.min(workerThreads, 16));
        this.workers = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(WORKER_QUEUE_SIZE), new NamedThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Scheduled(initialDelay = 1000, fixedDelay = 5000)
    public void scan() {
        if (!enabled) return;
        service.failStaleRuns(maxRunSeconds);
        Set<Long> ready = new LinkedHashSet<>(service.pendingRunIds(SCAN_BATCH_SIZE));
        while (ready.size() < SCAN_BATCH_SIZE) {
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
                        try {
                            ScheduledExecutionToken token = ScheduledExecutionToken.issue();
                            String expiresAt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                                    .withZone(ZoneOffset.UTC)
                                    .format(Instant.now().plusSeconds(tokenTtlSeconds));
                            if (!service.beginRun(runId, token.hash(), expiresAt)) return;
                            long startedAt = System.nanoTime();
                            LOG.info("Scheduled run started run_id={}", runId);
                            service.completeFromProxy(runId, execute(token));
                            LOG.info("Scheduled run completed run_id={} duration_ms={}", runId,
                                    (System.nanoTime() - startedAt) / 1_000_000L);
                        } catch (ScheduledAgentFailure error) {
                            LOG.warn("Scheduled run failed run_id={} error_code={}",
                                    runId, error.code);
                            try {
                                service.failRun(runId, error.code, error.userMessage);
                            } catch (RuntimeException finishError) {
                                LOG.error("Scheduled run failure persistence failed run_id={}", runId);
                            }
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

    private Map<String, Object> execute(ScheduledExecutionToken token) {
        try {
            AgentResponse response = proxy.scheduled(token.authorizationHeader());
            if (response.getStatus() < 200 || response.getStatus() >= 300)
                throw failure(response);
            String body = response.getBody();
            if (body == null || body.isEmpty() || body.length() > 4000000)
                throw new IllegalStateException("Agent scheduled 返回无效结果");
            Map<String, Object> result = json.readValue(
                    body, new TypeReference<Map<String, Object>>() {});
            required(result, "content");
            return result;
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Agent scheduled 请求或响应无效", error);
        }
    }

    private static String required(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty())
            throw new IllegalStateException("Scheduled run result 缺少 " + key);
        return (String) value;
    }

    private ScheduledAgentFailure failure(AgentResponse response) {
        String code = "agent_http_error";
        String message = "Agent 服务返回 HTTP " + response.getStatus();
        String body = response.getBody();
        if (body != null && !body.isEmpty() && body.length() <= 65536) {
            try {
                Map<String, Object> error = json.readValue(
                        body, new TypeReference<Map<String, Object>>() {});
                code = safeCode(error.get("code"), code);
                message = safeMessage(error.get("error"), message);
            } catch (Exception ignored) {}
        }
        return new ScheduledAgentFailure(code, message);
    }

    private static String safeCode(Object value, String fallback) {
        if (!(value instanceof String)) return fallback;
        String code = ((String) value).trim();
        return code.matches("[A-Za-z0-9._-]{1,64}") ? code : fallback;
    }

    private static String safeMessage(Object value, String fallback) {
        if (!(value instanceof String)) return fallback;
        String message = ((String) value).trim().replaceAll("\\s+", " ");
        if (message.isEmpty()) return fallback;
        return message.substring(0, Math.min(message.length(), 512));
    }

    @PreDestroy
    public void close() { workers.shutdownNow(); }

    private static final class ScheduledAgentFailure extends IllegalStateException {
        private final String code;
        private final String userMessage;

        private ScheduledAgentFailure(String code, String userMessage) {
            this.code = code;
            this.userMessage = userMessage;
        }
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
