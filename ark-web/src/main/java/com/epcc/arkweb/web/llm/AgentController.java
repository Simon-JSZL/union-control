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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.LinkedHashMap;

/** Internal Agent API used by py-app; CAS and scheduled requests use separate guards. */
@RestController
@RequestMapping("/agent")
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

    /** Returns the Scheduled identity already validated by the Shiro Agent filter. */
    @PostMapping("/scheduledTaskAuthorize")
    public ResponseEntity<Map<String, Object>> scheduledTaskAuthorize(
            @RequestBody(required = false) Object payload) {
        if (payload != null && (!(payload instanceof Map) || !((Map<?, ?>) payload).isEmpty()))
            return unauthorized();
        Map<String, Object> context = AgentAuthorizationFilter.currentContext();
        if (context == null) return unauthorized();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(success(context));
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
    public Map<String, Object> messages(
            @RequestBody Map<String, Object> payload) {
        return conversations.conversationMessages(request.json(payload));
    }

    @PostMapping("/completeRun")
    public Map<String, Object> completeRun(
            @RequestBody Map<String, Object> payload) {
        return executions.completeRun(request.json(payload));
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

    @PostMapping("/queryChanges")
    public Map<String, Object> queryChanges(@RequestBody Map<String, Object> payload) {
        return runningAnalysis.queryChanges(request.json(payload));
    }

    @PostMapping("/queryFaults")
    public Map<String, Object> queryFaults(@RequestBody Map<String, Object> payload) {
        return runningAnalysis.queryFaults(request.json(payload));
    }

    @ExceptionHandler(RunningAnalysisMockService.QueryException.class)
    public ResponseEntity<Map<String, Object>> queryError(RunningAnalysisMockService.QueryException error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", error.getCode());
        body.put("error", "运维查询未执行，请核对条件及 mock 支持范围。");
        return ResponseEntity.status(error.getStatus()).body(body);
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

}
