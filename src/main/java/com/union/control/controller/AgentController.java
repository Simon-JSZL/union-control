package com.union.control.controller;

import com.union.control.service.ControlService;
import com.union.control.service.RunningAnalysisMockService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Internal Cookie-authenticated API used only by union-py-app. */
@RestController
@RequestMapping("/agent")
public class AgentController {
    private final ControlService service;
    private final RunningAnalysisMockService runningAnalysis;

    @Autowired
    public AgentController(ControlService service) {
        this(service, new RunningAnalysisMockService());
    }

    AgentController(ControlService service, RunningAnalysisMockService runningAnalysis) {
        this.service = service;
        this.runningAnalysis = runningAnalysis;
    }

    @PostMapping("/getConversationMessages")
    public Map<String, Object> messages(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        return service.conversationMessages(cookie, payload);
    }

    @PostMapping("/completeRun")
    public Map<String, Object> completeRun(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        return service.completeRun(cookie, payload);
    }

    @PostMapping("/rootExecutionSelected")
    public Map<String, Object> rootExecutionSelected(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        return service.rootExecutionSelected(cookie, payload);
    }

    @PostMapping("/executionStarted")
    public Map<String, Object> executionStarted(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        return service.executionStarted(cookie, payload);
    }

    @PostMapping("/executionFinished")
    public Map<String, Object> executionFinished(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        return service.executionFinished(cookie, payload);
    }

    @PostMapping("/memoryStore/read")
    public Map<String, Object> memoryRead(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        return service.memoryRead(cookie, payload);
    }

    @PostMapping("/memoryStore/list")
    public Map<String, Object> memoryList(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        return service.memoryList(cookie, payload);
    }

    @PostMapping("/memoryStore/operation")
    public Map<String, Object> memoryOperation(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        return service.memoryOperation(cookie, payload);
    }

    @PostMapping("/memoryStore/write")
    public Map<String, Object> memoryWrite(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        return service.memoryWrite(cookie, payload);
    }

    @PostMapping("/memoryStore/delete")
    public Map<String, Object> memoryDelete(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        return service.memoryDelete(cookie, payload);
    }

    @PostMapping("/memoryStore/search")
    public Map<String, Object> memorySearch(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        return service.memorySearch(cookie, payload);
    }

    @PostMapping("/getOrgInfo")
    public Map<String, Object> getOrgInfo(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        return runningAnalysis.getOrgInfo(cookie, payload);
    }

    @PostMapping("/queryBigData")
    public Map<String, Object> queryBigData(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        return runningAnalysis.queryBigData(cookie, payload);
    }

    @PostMapping("/announceList")
    public Map<String, Object> announceList(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        return runningAnalysis.announceList(cookie, payload);
    }

    @PostMapping("/getJiraInfo")
    public Map<String, Object> getJiraInfo(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        return runningAnalysis.getJiraInfo(cookie, payload);
    }
}
