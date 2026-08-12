package com.union.control.scheduled;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.service.LocalAuth;
import com.union.control.service.ControlService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronSequenceGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

@Service
public class ScheduledTaskService {
    private static final int MAX_USER_TASKS = 100;
    private static final int CRON_INTERVAL_CHECKS = 32;
    private static final List<String> TYPES = Arrays.asList("ONCE", "CRON", "INTERVAL");
    private static final List<String> TASK_STATUSES = Arrays.asList("ACTIVE", "PAUSED", "COMPLETED");
    private final ScheduledTaskMapper mapper;
    private final ObjectMapper json;
    private final long minimumIntervalSeconds;
    private final ControlService controlService;

    @Autowired
    public ScheduledTaskService(
            ScheduledTaskMapper mapper,
            ObjectMapper json,
            @Value("${agent.scheduled-minimum-interval-seconds:60}") long minimumIntervalSeconds,
            ControlService controlService) {
        this.mapper = mapper;
        this.json = json;
        this.minimumIntervalSeconds = minimumIntervalSeconds;
        this.controlService = controlService;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> create(String cookie, Map<String, Object> payload) {
        String userId = LocalAuth.authenticate(cookie);
        mapper.lockUserTasks(userId);
        if (mapper.countRunnableTasks(userId) >= MAX_USER_TASKS)
            throw new ConflictException("每个用户最多保留 100 个可运行任务");
        Map<String, Object> task = taskDefinition(payload, userId);
        mapper.insertTask(task);
        return detail(cookie, number(task.get("id")));
    }

    @Transactional
    public Map<String, Object> update(String cookie, Map<String, Object> payload) {
        String userId = LocalAuth.authenticate(cookie);
        long taskId = positiveLong(payload == null ? null : payload.get("taskId"), "taskId");
        requireTaskOwner(taskId, userId, true);
        Map<String, Object> task = taskDefinition(payload, userId);
        task.put("taskId", taskId);
        if (mapper.updateTask(task) != 1) throw new NotFoundException("定时任务不存在");
        return detail(cookie, taskId);
    }

    public Map<String, Object> list(
            String cookie, String keyword, String status, int page, int pageSize) {
        String userId = LocalAuth.authenticate(cookie);
        page(page, pageSize);
        String normalizedStatus = status == null || status.trim().isEmpty()
                ? null : upper(status.trim());
        if (normalizedStatus != null && !TASK_STATUSES.contains(normalizedStatus))
            throw new IllegalArgumentException("status 非法");
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        if (normalizedKeyword.length() > 255) throw new IllegalArgumentException("keyword 非法");
        String like = normalizedKeyword.isEmpty()
                ? null : "%" + escapeLike(normalizedKeyword) + "%";
        long total = mapper.countTasks(userId, like, normalizedStatus);
        List<Map<String, Object>> items = mapper.findTasks(
                userId, like, normalizedStatus, pageSize, (page - 1) * pageSize);
        return ok(pageData(items, total, page, pageSize));
    }

    public Map<String, Object> detail(String cookie, long taskId) {
        String userId = LocalAuth.authenticate(cookie);
        Map<String, Object> task = mapper.findTaskDetail(taskId, userId);
        if (task == null) throw new NotFoundException("定时任务不存在");
        return ok(task);
    }

    public Map<String, Object> runs(String cookie, long taskId, int page, int pageSize) {
        String userId = LocalAuth.authenticate(cookie);
        page(page, pageSize);
        requireTaskOwner(taskId, userId, false);
        long total = mapper.countRuns(taskId, userId);
        List<Map<String, Object>> rows = mapper.findRuns(
                taskId, userId, pageSize, (page - 1) * pageSize);
        return ok(pageData(publicRuns(rows), total, page, pageSize));
    }

    public Map<String, Object> runDetail(String cookie, long runId) {
        String userId = LocalAuth.authenticate(cookie);
        Map<String, Object> row = ownedRun(runId, userId, false);
        if (row == null) throw new NotFoundException("运行记录不存在");
        return ok(publicRun(row, true));
    }

    public Map<String, Object> unread(String cookie) {
        String userId = LocalAuth.authenticate(cookie);
        long total = mapper.countUnread(userId);
        List<Map<String, Object>> rows = mapper.findUnread(userId);
        List<Map<String, Object>> items = publicRuns(rows);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("count", total);
        return ok(data);
    }

    @Transactional
    public Map<String, Object> start(String cookie, long taskId) {
        String userId = LocalAuth.authenticate(cookie);
        Map<String, Object> task = requireTaskOwner(taskId, userId, true);
        if (!"PAUSED".equals(string(task, "status")))
            throw new ConflictException("只有暂停任务可以启动");
        Instant now = Instant.now();
        Instant next = nextForTask(task, now, true);
        mapper.activateTask(taskId, userId, databaseDateTime(next));
        return detail(cookie, taskId);
    }

    @Transactional
    public Map<String, Object> pause(String cookie, long taskId) {
        String userId = LocalAuth.authenticate(cookie);
        requireTaskOwner(taskId, userId, true);
        int changed = mapper.pauseTask(taskId, userId);
        if (changed != 1) throw new ConflictException("只有运行中的任务可以暂停");
        return detail(cookie, taskId);
    }

    @Transactional
    public Map<String, Object> discard(String cookie, long taskId) {
        String userId = LocalAuth.authenticate(cookie);
        requireTaskOwner(taskId, userId, true);
        mapper.discardTask(taskId, userId);
        return ok(null);
    }

    @Transactional
    public Long claimDueRun() {
        Map<String, Object> task = mapper.findDueTask();
        if (task == null) return null;
        long taskId = number(task.get("id"));
        Instant scheduledAt = instant(task.get("nextRunAt"));
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("taskId", taskId);
        run.put("scheduledAt", databaseDateTime(scheduledAt));
        mapper.insertRun(run);
        String type = string(task, "scheduleType");
        if ("ONCE".equals(type)) {
            mapper.completeOnceTask(taskId);
        } else {
            Instant next = nextForTask(task, Instant.now(), false);
            mapper.updateNextRun(taskId, databaseDateTime(next));
        }
        return number(run.get("id"));
    }

    public List<Long> pendingRunIds(int limit) {
        return mapper.findPendingRunIds(limit);
    }

    public boolean beginRun(long runId) {
        return mapper.beginRun(runId) == 1;
    }

    public Map<String, Object> executionContext(long runId) {
        Map<String, Object> row = mapper.findRunContext(runId);
        if (row == null) throw new NotFoundException("运行记录不存在或不可执行");
        String owner = string(row, "userId");
        if (owner == null || owner.trim().isEmpty()) throw new DataCorruptionException();
        return row;
    }

    @Transactional
    public void completeFromProxy(long runId, Map<String, Object> result) {
        Map<String, Object> run = lockRun(runId);
        if (terminal(string(run, "status"))) return;
        if (!"RUNNING".equals(string(run, "status")))
            throw new ConflictException("运行状态不可完成");
        String content = rawText(result, "content", 1000000, true);
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("content", content);
        stored.put("agentName", optionalText(
                result, "agentName", 128, "ScheduledTaskAgent"));
        try {
            String value = json.writeValueAsString(stored);
            if (value.length() > 4000000) throw new IllegalArgumentException("运行结果过大");
            mapper.completeRun(runId, value);
            completeOnceTask(number(run.get("taskId")));
        } catch (Exception error) {
            if (error instanceof RuntimeException) throw (RuntimeException) error;
            throw new IllegalArgumentException("运行结果无法保存");
        }
    }

    @Transactional
    public void failRun(long runId, String errorCode, String errorMessage) {
        Map<String, Object> run = lockRun(runId);
        if (terminal(string(run, "status"))) return;
        mapper.failRun(runId,
                safeError(errorCode, "scheduled_run_failed", 64),
                safeError(errorMessage, "定时任务执行失败", 512));
        completeOnceTask(number(run.get("taskId")));
    }

    public int failStaleRuns(int maxRunSeconds) {
        return mapper.failStaleRuns(maxRunSeconds);
    }

    @Transactional
    public Map<String, Object> open(String cookie, long runId) {
        String userId = LocalAuth.authenticate(cookie);
        Map<String, Object> run = ownedRun(runId, userId, true);
        if (run == null) throw new NotFoundException("运行记录不存在");
        Object existing = run.get("resultConversationId");
        if (existing != null && !String.valueOf(existing).isEmpty()) {
            mapper.markRunRead(runId);
            return ok(Collections.<String, Object>singletonMap(
                    "conversationId", String.valueOf(existing)));
        }
        String status = string(run, "status");
        if (!terminal(status)) throw new ConflictException("运行尚未结束");
        Map<String, Object> result = result(run.get("resultPayload"));
        String content = "SUCCEEDED".equals(status)
                ? rawText(result, "content", 1000000, true)
                : safeError(string(run, "errorMessage"), "定时任务执行失败", 512);
        String conversationId = controlService.materializeCompletedConversation(
                cookie, string(run, "title"), string(run, "prompt"), content,
                optionalText(result, "agentName", 128, "ScheduledTaskAgent"));
        mapper.attachConversation(runId, conversationId);
        Map<String, Object> opened = new LinkedHashMap<>();
        opened.put("conversationId", conversationId);
        return ok(opened);
    }

    private ScheduleValues scheduleValues(
            Map<String, Object> payload, String type, ZoneId zone, Instant now) {
        Instant runAt = parseInstant(text(payload, "runAt", 64, false), zone);
        String cron = text(payload, "cronExpression", 128, false);
        Long interval = optionalLong(payload.get("intervalSeconds"));
        if ("ONCE".equals(type)) {
            if (runAt == null || !runAt.isAfter(now) || cron != null || interval != null)
                throw new IllegalArgumentException("ONCE 调度字段非法");
            return new ScheduleValues(runAt, null, null, runAt);
        }
        if ("CRON".equals(type)) {
            if (cron == null || runAt != null || interval != null)
                throw new IllegalArgumentException("CRON 调度字段非法");
            validateCron(cron, zone);
            return new ScheduleValues(null, cron, null, nextCron(cron, zone, now));
        }
        if (runAt == null || interval == null || cron != null)
            throw new IllegalArgumentException("INTERVAL 调度字段非法");
        return new ScheduleValues(runAt, null, interval,
                nextInterval(runAt, interval, now));
    }

    private Map<String, Object> taskDefinition(Map<String, Object> payload, String userId) {
        String title = text(payload, "title", 255, true);
        String prompt = text(payload, "prompt", 4096, true);
        String type = upper(text(payload, "scheduleType", 16, true));
        if (!TYPES.contains(type)) throw new IllegalArgumentException("scheduleType 非法");
        ZoneId zone = zone(text(payload, "timezone", 64, true));
        ScheduleValues values = scheduleValues(payload, type, zone, Instant.now());
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("userId", userId);
        task.put("title", title);
        task.put("prompt", prompt);
        task.put("scheduleType", type);
        task.put("runAt", databaseDateTime(values.runAt));
        task.put("cronExpression", values.cronExpression);
        task.put("intervalSeconds", values.intervalSeconds);
        task.put("timezone", zone.getId());
        task.put("nextRunAt", databaseDateTime(values.nextRunAt));
        return task;
    }

    private Instant nextForTask(Map<String, Object> task, Instant now, boolean starting) {
        String type = string(task, "scheduleType");
        Instant anchor = instant(task.get("runAt"));
        if ("ONCE".equals(type)) return anchor.isAfter(now) ? anchor : now;
        if ("CRON".equals(type)) return nextCron(
                string(task, "cronExpression"), zone(string(task, "timezone")), now);
        long interval = number(task.get("intervalSeconds"));
        return nextInterval(anchor, interval, now);
    }

    void validateCron(String expression, ZoneId timezone) {
        CronSequenceGenerator generator;
        try {
            generator = new CronSequenceGenerator(
                    expression, TimeZone.getTimeZone(timezone));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("cronExpression 非法");
        }
        Date previous = generator.next(new Date());
        for (int index = 0; index < CRON_INTERVAL_CHECKS; index++) {
            Date next = generator.next(previous);
            if (Duration.between(previous.toInstant(), next.toInstant()).getSeconds()
                    < minimumIntervalSeconds)
                throw new IllegalArgumentException(
                        "cronExpression 触发间隔不能小于 " + minimumIntervalSeconds + " 秒");
            previous = next;
        }
    }

    Instant nextCron(String expression, ZoneId timezone, Instant after) {
        validateCron(expression, timezone);
        return new CronSequenceGenerator(expression, TimeZone.getTimeZone(timezone))
                .next(Date.from(after)).toInstant();
    }

    Instant nextInterval(Instant anchor, long intervalSeconds, Instant after) {
        if (intervalSeconds < minimumIntervalSeconds)
            throw new IllegalArgumentException(
                    "intervalSeconds 不能小于 " + minimumIntervalSeconds);
        if (anchor.isAfter(after)) return anchor;
        long intervalMillis = Math.multiplyExact(intervalSeconds, 1000L);
        long elapsedMillis = Duration.between(anchor, after).toMillis();
        long steps = elapsedMillis / intervalMillis + 1;
        return anchor.plusMillis(Math.multiplyExact(steps, intervalMillis));
    }

    private Map<String, Object> requireTaskOwner(long taskId, String userId, boolean lock) {
        Map<String, Object> task = mapper.findTaskOwner(taskId, userId, lock);
        if (task == null) throw new NotFoundException("定时任务不存在");
        return task;
    }

    private Map<String, Object> ownedRun(long runId, String userId, boolean lock) {
        return mapper.findOwnedRun(runId, userId, lock);
    }

    private Map<String, Object> lockRun(long runId) {
        Map<String, Object> run = mapper.findRunForUpdate(runId);
        if (run == null) throw new NotFoundException("运行记录不存在");
        return run;
    }

    private void completeOnceTask(long taskId) {
        mapper.completeOnceTask(taskId);
    }

    private List<Map<String, Object>> publicRuns(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) result.add(publicRun(row, false));
        return result;
    }

    private Map<String, Object> publicRun(Map<String, Object> row, boolean includeResultContent) {
        Map<String, Object> value = new LinkedHashMap<>(row);
        Object raw = value.remove("resultPayload");
        if (includeResultContent) value.put("resultContent", result(raw).get("content"));
        value.put("readFlag", numberOrZero(value.get("readFlag")) == 1);
        return value;
    }

    private Map<String, Object> result(Object raw) {
        if (raw == null) return Collections.emptyMap();
        try {
            return json.readValue(String.valueOf(raw), new TypeReference<Map<String, Object>>() {});
        } catch (Exception error) {
            throw new DataCorruptionException();
        }
    }

    private static Map<String, Object> pageData(
            List<Map<String, Object>> items, long total, int page, int pageSize) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("total", total);
        data.put("page", page);
        data.put("pageSize", pageSize);
        return data;
    }

    private static void page(int page, int pageSize) {
        if (page < 1 || page > 100000) throw new IllegalArgumentException("page 非法");
        if (pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("pageSize 非法");
    }

    private static String text(Map<?, ?> values, String key, int max, boolean required) {
        Object raw = values == null ? null : values.get(key);
        if (raw != null && !(raw instanceof String)) throw new IllegalArgumentException(key + " 非法");
        String value = raw == null ? null : ((String) raw).trim();
        if (value != null && value.isEmpty()) value = null;
        if ((required && value == null) || (value != null && value.length() > max))
            throw new IllegalArgumentException(key + " 非法");
        return value;
    }

    private static String rawText(Map<?, ?> values, String key, int max, boolean required) {
        Object raw = values == null ? null : values.get(key);
        if (raw != null && !(raw instanceof String)) throw new IllegalArgumentException(key + " 非法");
        String value = raw == null ? null : (String) raw;
        if ((required && (value == null || value.trim().isEmpty())) ||
                (value != null && value.length() > max))
            throw new IllegalArgumentException(key + " 非法");
        return value;
    }

    private static String optionalText(
            Map<String, Object> values, String key, int max, String fallback) {
        String value = text(values, key, max, false);
        return value == null ? fallback : value;
    }

    private static String string(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) value = values.get(key.toUpperCase(Locale.ROOT));
        return value == null ? null : String.valueOf(value);
    }

    private static String upper(String value) {
        return value.toUpperCase(Locale.ROOT);
    }

    static ZoneId zone(String value) {
        if (value == null || !ZoneId.getAvailableZoneIds().contains(value))
            throw new IllegalArgumentException("timezone 非法");
        return ZoneId.of(value);
    }

    private static Instant parseInstant(String value, ZoneId zone) {
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException ignoredAgain) {
                try {
                    return LocalDateTime.parse(value).atZone(zone).toInstant();
                } catch (DateTimeParseException error) {
                    throw new IllegalArgumentException("runAt 非法");
                }
            }
        }
    }

    static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp) return ((Timestamp) value).toInstant();
        if (value instanceof java.util.Date) return ((java.util.Date) value).toInstant();
        if (value instanceof LocalDateTime)
            return ((LocalDateTime) value).toInstant(ZoneOffset.UTC);
        return Instant.parse(String.valueOf(value));
    }

    static LocalDateTime databaseDateTime(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Long optionalLong(Object value) {
        if (value == null) return null;
        if (!(value instanceof Number)) throw new IllegalArgumentException("intervalSeconds 非法");
        long result = ((Number) value).longValue();
        if (result <= 0) throw new IllegalArgumentException("intervalSeconds 非法");
        return result;
    }

    private static long positiveLong(Object value, String name) {
        if (!(value instanceof Number) || ((Number) value).longValue() <= 0)
            throw new IllegalArgumentException(name + " 非法");
        return ((Number) value).longValue();
    }

    private static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : Long.parseLong(String.valueOf(value));
    }

    private static long numberOrZero(Object value) {
        if (value instanceof Boolean) return (Boolean) value ? 1 : 0;
        return value == null ? 0 : number(value);
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static boolean terminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status);
    }

    private static String safeError(String value, String fallback, int max) {
        if (value == null || value.trim().isEmpty()) return fallback;
        String normalized = value.trim();
        return normalized.substring(0, Math.min(normalized.length(), max));
    }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        return response;
    }

    private static class ScheduleValues {
        private final Instant runAt;
        private final String cronExpression;
        private final Long intervalSeconds;
        private final Instant nextRunAt;

        private ScheduleValues(Instant runAt, String cronExpression,
                               Long intervalSeconds, Instant nextRunAt) {
            this.runAt = runAt;
            this.cronExpression = cronExpression;
            this.intervalSeconds = intervalSeconds;
            this.nextRunAt = nextRunAt;
        }
    }

    public static class UnauthorizedException extends RuntimeException {}
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }
    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) { super(message); }
    }
    public static class DataCorruptionException extends RuntimeException {}
}
