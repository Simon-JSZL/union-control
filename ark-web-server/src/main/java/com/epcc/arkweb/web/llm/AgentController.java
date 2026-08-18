package com.epcc.arkweb.web.llm;

import com.union.control.service.AgentExecutionService;
import com.union.control.service.ConversationService;
import com.union.control.service.MemoryStoreService;
import com.union.control.service.RunningAnalysisMockService;
import com.epcc.arkweb.helper.AuthenticatedRequest;
import com.union.control.security.ScheduledExecutionRealm;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authz.UnauthenticatedException;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final AuthenticatedRequest request;

    @Autowired
    public AgentController(
            ConversationService conversations,
            AgentExecutionService executions,
            MemoryStoreService memoryStore,
            RunningAnalysisMockService runningAnalysis,
            AuthenticatedRequest request) {
        this.conversations = conversations;
        this.executions = executions;
        this.memoryStore = memoryStore;
        this.runningAnalysis = runningAnalysis;
        this.request = request;
    }

    @PostMapping("/scheduledExecutionIdentity")
    public ResponseEntity<Map<String, Object>> scheduledExecutionIdentity(
            @RequestBody Object payload) {
        if (!(payload instanceof Map) || !((Map<?, ?>) payload).isEmpty())
            throw new InvalidScheduledRequestException();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", currentExecutionIdentity());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    public static final class InvalidScheduledRequestException extends RuntimeException {}

    @PostMapping("/getConversationMessages")
    public Map<String, Object> messages(
            @RequestBody Map<String, Object> payload) {
        return conversations.conversationMessages(request.json(payload));
    }

    @PostMapping("/completeRun")
    public Map<String, Object> completeRun(
            @RequestBody Map<String, Object> payload) {
        return executions.completeRun(request.json(payload));
    }

    @PostMapping("/rootExecutionSelected")
    public Map<String, Object> rootExecutionSelected(
            @RequestBody Map<String, Object> payload) {
        return executions.rootExecutionSelected(request.json(payload));
    }

    @PostMapping("/executionStarted")
    public Map<String, Object> executionStarted(
            @RequestBody Map<String, Object> payload) {
        return executions.executionStarted(request.json(payload));
    }

    @PostMapping("/executionFinished")
    public Map<String, Object> executionFinished(
            @RequestBody Map<String, Object> payload) {
        return executions.executionFinished(request.json(payload));
    }

    @PostMapping("/memoryStore/read")
    public Map<String, Object> memoryRead(
            @RequestBody Map<String, Object> payload) {
        return memoryStore.memoryRead(request.json(payload));
    }

    @PostMapping("/memoryStore/list")
    public Map<String, Object> memoryList(
            @RequestBody Map<String, Object> payload) {
        return memoryStore.memoryList(request.json(payload));
    }

    @PostMapping("/memoryStore/operation")
    public Map<String, Object> memoryOperation(
            @RequestBody Map<String, Object> payload) {
        return memoryStore.memoryOperation(request.json(payload));
    }

    @PostMapping("/memoryStore/write")
    public Map<String, Object> memoryWrite(
            @RequestBody Map<String, Object> payload) {
        return memoryStore.memoryWrite(request.json(payload));
    }

    @PostMapping("/memoryStore/delete")
    public Map<String, Object> memoryDelete(
            @RequestBody Map<String, Object> payload) {
        return memoryStore.memoryDelete(request.json(payload));
    }

    @PostMapping("/memoryStore/search")
    public Map<String, Object> memorySearch(
            @RequestBody Map<String, Object> payload) {
        return memoryStore.memorySearch(request.json(payload));
    }

    @PostMapping("/getOrgInfo")
    public Map<String, Object> getOrgInfo(
            @RequestBody Map<String, Object> payload) {
        return runningAnalysis.getOrgInfo(request.json(payload));
    }

    @PostMapping("/queryBigData")
    public Map<String, Object> queryBigData(
            @RequestBody Map<String, Object> payload) {
        return runningAnalysis.queryBigData(request.json(payload));
    }

    @PostMapping("/announceList")
    public Map<String, Object> announceList(
            @RequestBody Map<String, Object> payload) {
        return runningAnalysis.announceList(request.json(payload));
    }

    @PostMapping("/getJiraInfo")
    public Map<String, Object> getJiraInfo(
            @RequestBody Map<String, Object> payload) {
        return runningAnalysis.getJiraInfo(request.json(payload));
    }

    private static Map<String, Object> currentExecutionIdentity() {
        Object current = SecurityUtils.getSubject().getPrincipal();
        if (!(current instanceof ScheduledExecutionRealm.Principal))
            throw new UnauthenticatedException();
        ScheduledExecutionRealm.Principal principal =
                (ScheduledExecutionRealm.Principal) current;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("authenticationType", principal.getAuthenticationType());
        data.put("runId", principal.getRunId());
        data.put("taskId", principal.getTaskId());
        data.put("userId", principal.getLoginName());
        data.put("orgCode", principal.getOrgCode());
        data.put("prompt", principal.getPrompt());
        data.put("scheduledAt", principal.getScheduledAt().toString());
        data.put("timezone", principal.getTimezone());
        data.put("expiresAt", principal.getExpiresAt().toString());
        return data;
    }
}
