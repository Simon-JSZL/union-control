package com.epcc.arkweb.web.llm;

import com.union.control.service.AgentExecutionService;
import com.union.control.service.ConversationService;
import com.union.control.service.MemoryStoreService;
import com.union.control.service.RunningAnalysisMockService;
import com.epcc.arkweb.helper.AuthenticatedRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.LinkedHashMap;

/** Internal Agent API used by py-app; CAS and scheduled requests use separate guards. */
@RestController
@RequestMapping("/agent")
public class AgentController {
    private static final String EXECUTE_PERMISSION = "/assistantManager/page";
    private final ConversationService conversations;
    private final AgentExecutionService executions;
    private final MemoryStoreService memoryStore;
    private final RunningAnalysisMockService runningAnalysis;
    private final AuthenticatedRequest request;
    private final AgentAuthorizationInterceptor authorization;

    @Autowired
    public AgentController(
            ConversationService conversations,
            AgentExecutionService executions,
            MemoryStoreService memoryStore,
            RunningAnalysisMockService runningAnalysis,
            AuthenticatedRequest request,
            AgentAuthorizationInterceptor authorization) {
        this.conversations = conversations;
        this.executions = executions;
        this.memoryStore = memoryStore;
        this.runningAnalysis = runningAnalysis;
        this.request = request;
        this.authorization = authorization;
    }

    /** Validates the run-scoped token and performs the real-time role-resource check. */
    @PostMapping("/scheduledTaskAuthorize")
    public ResponseEntity<Map<String, Object>> scheduledTaskAuthorize(
            @org.springframework.web.bind.annotation.RequestHeader(
                    value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) Object payload) {
        if (payload != null && (!(payload instanceof Map) || !((Map<?, ?>) payload).isEmpty()))
            return unauthorized();
        try {
            Map<String, Object> context = this.authorization.authorize(
                    authorization, EXECUTE_PERMISSION);
            Map<String, Object> data = new LinkedHashMap<>(context);
            // The value is an opaque, database-backed capability for subsequent tool calls.
            data.put("trustedContext", authorization);
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(success(data));
        } catch (RuntimeException error) {
            return unauthorized();
        }
    }

    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(401).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(java.util.Collections.<String, Object>singletonMap(
                        "error", "scheduled_auth_error"));
    }

    private static Map<String, Object> success(Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return body;
    }

    @PostMapping("/getConversationMessages")
    @AgentPermission("/assistantManager/page")
    public Map<String, Object> messages(
            @RequestBody Map<String, Object> payload) {
        return conversations.conversationMessages(request.json(payload));
    }

    @PostMapping("/completeRun")
    @AgentPermission("/assistantManager/page")
    public Map<String, Object> completeRun(
            @RequestBody Map<String, Object> payload) {
        return executions.completeRun(request.json(payload));
    }

    @PostMapping("/memoryStore/read")
    @AgentPermission("/assistantManager/page")
    public Map<String, Object> memoryRead(
            @RequestBody Map<String, Object> payload) {
        return memoryStore.memoryRead(request.json(payload));
    }

    @PostMapping("/memoryStore/list")
    @AgentPermission("/assistantManager/page")
    public Map<String, Object> memoryList(
            @RequestBody Map<String, Object> payload) {
        return memoryStore.memoryList(request.json(payload));
    }

    @PostMapping("/memoryStore/operation")
    @AgentPermission("/assistantManager/page")
    public Map<String, Object> memoryOperation(
            @RequestBody Map<String, Object> payload) {
        return memoryStore.memoryOperation(request.json(payload));
    }

    @PostMapping("/memoryStore/write")
    @AgentPermission("/assistantManager/page")
    public Map<String, Object> memoryWrite(
            @RequestBody Map<String, Object> payload) {
        return memoryStore.memoryWrite(request.json(payload));
    }

    @PostMapping("/memoryStore/delete")
    @AgentPermission("/assistantManager/page")
    public Map<String, Object> memoryDelete(
            @RequestBody Map<String, Object> payload) {
        return memoryStore.memoryDelete(request.json(payload));
    }

    @PostMapping("/memoryStore/search")
    @AgentPermission("/assistantManager/page")
    public Map<String, Object> memorySearch(
            @RequestBody Map<String, Object> payload) {
        return memoryStore.memorySearch(request.json(payload));
    }

    @PostMapping("/getOrgInfo")
    @AgentPermission("/assistantManager/page")
    public Map<String, Object> getOrgInfo(
            @RequestBody Map<String, Object> payload) {
        return runningAnalysis.getOrgInfo(request.json(payload));
    }

    @PostMapping("/queryBigData")
    @AgentPermission("/assistantManager/page")
    public Map<String, Object> queryBigData(
            @RequestBody Map<String, Object> payload) {
        return runningAnalysis.queryBigData(request.json(payload));
    }

    @PostMapping("/announceList")
    @AgentPermission("/assistantManager/page")
    public Map<String, Object> announceList(
            @RequestBody Map<String, Object> payload) {
        return runningAnalysis.announceList(request.json(payload));
    }

    @PostMapping("/getJiraInfo")
    @AgentPermission("/assistantManager/page")
    public Map<String, Object> getJiraInfo(
            @RequestBody Map<String, Object> payload) {
        return runningAnalysis.getJiraInfo(request.json(payload));
    }

}
