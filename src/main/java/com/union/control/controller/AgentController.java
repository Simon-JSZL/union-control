package com.union.control.controller;

import com.union.control.service.ControlService;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Internal API called by union-py-app. */
@RestController
@RequestMapping("/agent")
public class AgentController {
    private final ControlService service;

    public AgentController(ControlService service) {
        this.service = service;
    }

    @PostMapping("/conversationCreate")
    public Map<String, Object> create(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        return service.create(cookie, payload);
    }

    @GetMapping("/getConversationItems")
    public Map<String, Object> items(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestParam String conversationId,
            @RequestParam(required = false) Integer limit) {
        return service.items(cookie, conversationId, limit);
    }

    @PostMapping("/addConversationItems")
    public Map<String, Object> appendItems(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestParam String conversationId,
            @RequestBody Map<String, Object> payload) {
        return service.appendItems(cookie, conversationId, payload);
    }

    @PostMapping("/deleteConversationItems")
    public Map<String, Object> clearItems(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestParam String conversationId) {
        return service.clearItems(cookie, conversationId);
    }

    @PostMapping("/popConversationItem")
    public Map<String, Object> popItem(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestParam String conversationId) {
        return service.popItem(cookie, conversationId);
    }

    @PostMapping("/executionClaim")
    public Map<String, Object> claimExecution(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        return service.claimExecution(cookie, payload);
    }

    @PostMapping("/executionHeartbeat")
    public Map<String, Object> heartbeatExecution(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        return service.heartbeatExecution(cookie, payload);
    }

    @PostMapping("/executionFinish")
    public Map<String, Object> finishExecution(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        return service.finishExecution(cookie, payload);
    }

    @GetMapping("/memoryList")
    public Map<String, Object> memories(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return service.memories(cookie, limit);
    }

    @PostMapping("/memorySave")
    public Map<String, Object> remember(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        return service.remember(cookie, payload);
    }

    @PostMapping("/memoryDelete")
    public Map<String, Object> forget(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestParam long memoryId) {
        return service.forget(cookie, memoryId);
    }
}
