package com.epcc.arkweb.web.llm;

import com.union.control.service.AgentExecutionService;
import com.union.control.service.ConversationService;
import com.union.control.service.MemoryStoreService;
import com.union.control.service.RunningAnalysisMockService;
import com.union.control.service.ScheduledTaskService;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.LinkedHashMap;

/** Internal Shiro-authenticated API used only by union-py-app. */
@RestController
@RequestMapping("/agent")
@RequiresPermissions(value = "/assistantManager/page")
public class AgentController {
    private final ConversationService conversations;
    private final AgentExecutionService executions;
    private final MemoryStoreService memoryStore;
    private final RunningAnalysisMockService runningAnalysis;
    private final ScheduledTaskService scheduledTasks;

    @Autowired
    public AgentController(
            ConversationService conversations,
            AgentExecutionService executions,
            MemoryStoreService memoryStore,
            RunningAnalysisMockService runningAnalysis,
            ScheduledTaskService scheduledTasks) {
        this.conversations = conversations;
        this.executions = executions;
        this.memoryStore = memoryStore;
        this.runningAnalysis = runningAnalysis;
        this.scheduledTasks = scheduledTasks;
    }

    @PostMapping("/scheduledExecutionIdentity")
    public ResponseEntity<Map<String, Object>> scheduledExecutionIdentity(
            @RequestBody Object payload) {
        if (!(payload instanceof Map) || !((Map<?, ?>) payload).isEmpty())
            throw new InvalidScheduledRequestException();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", scheduledTasks.currentExecutionIdentity());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    public static final class InvalidScheduledRequestException extends RuntimeException {}

    @PostMapping("/getConversationMessages")
    public Map<String, Object> messages(
            @RequestBody Map<String, Object> payload) {
        return conversations.conversationMessages(payload);
    }

    @PostMapping("/completeRun")
    public Map<String, Object> completeRun(
            @RequestBody Map<String, Object> payload) {
        return executions.completeRun(payload);
    }

    @PostMapping("/rootExecutionSelected")
    public Map<String, Object> rootExecutionSelected(
            @RequestBody Map<String, Object> payload) {
        return executions.rootExecutionSelected(payload);
    }

    @PostMapping("/executionStarted")
    public Map<String, Object> executionStarted(
            @RequestBody Map<String, Object> payload) {
        return executions.executionStarted(payload);
    }

    @PostMapping("/executionFinished")
    public Map<String, Object> executionFinished(
            @RequestBody Map<String, Object> payload) {
        return executions.executionFinished(payload);
    }

    @PostMapping("/memoryStore/read")
    public Map<String, Object> memoryRead(
            @RequestBody Map<String, Object> payload) {
        return memoryStore.memoryRead(payload);
    }

    @PostMapping("/memoryStore/list")
    public Map<String, Object> memoryList(
            @RequestBody Map<String, Object> payload) {
        return memoryStore.memoryList(payload);
    }

    @PostMapping("/memoryStore/operation")
    public Map<String, Object> memoryOperation(
            @RequestBody Map<String, Object> payload) {
        return memoryStore.memoryOperation(payload);
    }

    @PostMapping("/memoryStore/write")
    public Map<String, Object> memoryWrite(
            @RequestBody Map<String, Object> payload) {
        return memoryStore.memoryWrite(payload);
    }

    @PostMapping("/memoryStore/delete")
    public Map<String, Object> memoryDelete(
            @RequestBody Map<String, Object> payload) {
        return memoryStore.memoryDelete(payload);
    }

    @PostMapping("/memoryStore/search")
    public Map<String, Object> memorySearch(
            @RequestBody Map<String, Object> payload) {
        return memoryStore.memorySearch(payload);
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
