package com.union.control.scheduled;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class ScheduledTaskController {
    private final ScheduledTaskService service;

    public ScheduledTaskController(ScheduledTaskService service) {
        this.service = service;
    }

    @PostMapping(value = "/llm/scheduledTaskCreate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> create(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        return service.create(cookie, payload);
    }

    @PostMapping(value = "/llm/scheduledTaskUpdate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> update(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        return service.update(cookie, payload);
    }

    @GetMapping("/llm/scheduledTaskList")
    public Map<String, Object> list(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int pageSize) {
        return service.list(cookie, keyword, status, page, pageSize);
    }

    @GetMapping("/llm/scheduledTaskDetail")
    public Map<String, Object> detail(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestParam long taskId) {
        return service.detail(cookie, taskId);
    }

    @GetMapping("/llm/scheduledTaskRunList")
    public Map<String, Object> runs(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestParam long taskId,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int pageSize) {
        return service.runs(cookie, taskId, page, pageSize);
    }

    @GetMapping("/llm/scheduledTaskRunDetail")
    public Map<String, Object> runDetail(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestParam long runId) {
        return service.runDetail(cookie, runId);
    }

    @GetMapping("/llm/scheduledTaskUnread")
    public Map<String, Object> unread(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie) {
        return service.unread(cookie);
    }

    @PostMapping(value = "/llm/scheduledTaskStart", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> start(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        return service.start(cookie, id(payload, "taskId"));
    }

    @PostMapping(value = "/llm/scheduledTaskPause", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> pause(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        return service.pause(cookie, id(payload, "taskId"));
    }

    @PostMapping(value = "/llm/scheduledTaskDiscard", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> discard(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        return service.discard(cookie, id(payload, "taskId"));
    }

    @PostMapping(value = "/llm/scheduledTaskRunOpen", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> open(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        return service.open(cookie, id(payload, "runId"));
    }

    private static long id(Map<String, Object> payload, String name) {
        Object value = payload == null ? null : payload.get(name);
        if (!(value instanceof Number) || ((Number) value).longValue() <= 0)
            throw new IllegalArgumentException(name + " 非法");
        return ((Number) value).longValue();
    }

}
