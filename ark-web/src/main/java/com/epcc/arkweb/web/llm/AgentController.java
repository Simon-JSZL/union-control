package com.epcc.arkweb.web.llm;

import com.union.control.service.AgentExecutionService;
import com.union.control.service.ConversationService;
import com.union.control.service.MemoryStoreService;
import com.union.control.service.RunningAnalysisMockService;
import com.epcc.arkweb.helper.AuthContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.apache.shiro.authz.AuthorizationException;
import org.apache.shiro.authz.annotation.RequiresPermissions;
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
    private final ObjectMapper json;

    @Autowired
    public AgentController(
            ConversationService conversations,
            AgentExecutionService executions,
            MemoryStoreService memoryStore,
            RunningAnalysisMockService runningAnalysis,
            ObjectMapper json) {
        this.conversations = conversations;
        this.executions = executions;
        this.memoryStore = memoryStore;
        this.runningAnalysis = runningAnalysis;
        this.json = json;
    }

    /** Returns the Scheduled identity already validated by the Shiro Agent filter. */
    @RequiresPermissions(value = "/assistantManager/page")
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

    @RequiresPermissions(value = "/assistantManager/page")
    @PostMapping("/getConversationMessages")
    public Map<String, Object> messages(
            @RequestBody Map<String, Object> payload) {
        return conversations.conversationMessages(json.<ObjectNode>valueToTree(payload).put("userId", userId()).toString());
    }

    @RequiresPermissions(value = "/assistantManager/page")
    @PostMapping("/completeRun")
    public Map<String, Object> completeRun(
            @RequestBody Map<String, Object> payload) {
        return executions.completeRun(json.<ObjectNode>valueToTree(payload).put("userId", userId()).toString());
    }

    @RequiresPermissions(value = "/assistantManager/page")
    @PostMapping("/memoryStore/read")
    public Map<String, Object> memoryRead(
            @RequestBody Map<String, Object> payload) {
        return memoryStore.memoryRead(json.<ObjectNode>valueToTree(payload).put("userId", userId()).toString());
    }

    @RequiresPermissions(value = "/assistantManager/page")
    @PostMapping("/memoryStore/list")
    public Map<String, Object> memoryList(
            @RequestBody Map<String, Object> payload) {
        return memoryStore.memoryList(json.<ObjectNode>valueToTree(payload).put("userId", userId()).toString());
    }

    @RequiresPermissions(value = "/assistantManager/page")
    @PostMapping("/memoryStore/operation")
    public Map<String, Object> memoryOperation(
            @RequestBody Map<String, Object> payload) {
        return memoryStore.memoryOperation(json.<ObjectNode>valueToTree(payload).put("userId", userId()).toString());
    }

    @RequiresPermissions(value = "/assistantManager/page")
    @PostMapping("/memoryStore/write")
    public Map<String, Object> memoryWrite(
            @RequestBody Map<String, Object> payload) {
        return memoryStore.memoryWrite(json.<ObjectNode>valueToTree(payload).put("userId", userId()).toString());
    }

    @RequiresPermissions(value = "/assistantManager/page")
    @PostMapping("/memoryStore/delete")
    public Map<String, Object> memoryDelete(
            @RequestBody Map<String, Object> payload) {
        return memoryStore.memoryDelete(json.<ObjectNode>valueToTree(payload).put("userId", userId()).toString());
    }

    @RequiresPermissions(value = "/assistantManager/page")
    @PostMapping("/memoryStore/search")
    public Map<String, Object> memorySearch(
            @RequestBody Map<String, Object> payload) {
        return memoryStore.memorySearch(json.<ObjectNode>valueToTree(payload).put("userId", userId()).toString());
    }

    @RequiresPermissions(value = "/assistantManager/page")
    @PostMapping("/getOrgInfo")
    public Map<String, Object> getOrgInfo(
            @RequestBody Map<String, Object> payload) {
        return runningAnalysis.getOrgInfo(json.valueToTree(payload).toString());
    }

    @RequiresPermissions(value = "/assistantManager/page")
    @PostMapping("/queryBigData")
    public Map<String, Object> queryBigData(
            @RequestBody Map<String, Object> payload) {
        return runningAnalysis.queryBigData(json.valueToTree(payload).toString());
    }

    @RequiresPermissions(value = "/assistantManager/page")
    @PostMapping("/queryChanges")
    public Map<String, Object> queryChanges(@RequestBody Map<String, Object> payload) {
        return runningAnalysis.queryChanges(json.valueToTree(payload).toString());
    }

    @RequiresPermissions(value = "/assistantManager/page")
    @PostMapping("/queryFaults")
    public Map<String, Object> queryFaults(@RequestBody Map<String, Object> payload) {
        return runningAnalysis.queryFaults(json.valueToTree(payload).toString());
    }

    // Only owner-scoped Agent APIs need an identity in their business input.
    private static String userId() {
        Map<String, Object> scheduled = AgentAuthorizationFilter.currentContext();
        return scheduled == null ? AuthContextHolder.getAuthUserDetails().getLoginName()
                : (String) scheduled.get("userId");
    }

    @ExceptionHandler(AuthorizationException.class)
    public ResponseEntity<Map<String, Object>> permissionDenied() {
        return ResponseEntity.status(403).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(java.util.Collections.<String, Object>singletonMap(
                        "error", "agent_permission_denied"));
    }

    @ExceptionHandler(RunningAnalysisMockService.QueryException.class)
    public ResponseEntity<Map<String, Object>> queryError(RunningAnalysisMockService.QueryException error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", error.getCode());
        body.put("error", "运维查询未执行，请核对条件及 mock 支持范围。");
        return ResponseEntity.status(error.getStatus()).body(body);
    }

}
