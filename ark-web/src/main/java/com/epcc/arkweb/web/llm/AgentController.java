package com.epcc.arkweb.web.llm;

import com.union.control.service.AgentExecutionService;
import com.union.control.service.ConversationService;
import com.union.control.service.MemoryStoreService;
import com.union.control.service.RunningAnalysisMockService;
import com.epcc.arkweb.helper.AuthContextHolder;
import com.epcc.arkweb.utils.ResultMsg;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.apache.shiro.authz.AuthorizationException;
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
    private static final String NO_STORE = "no-store";
    private static final String USER_ID = "userId";

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

    /** Returns the Scheduled identity already validated by the Agent interceptor. */
    @AgentPermission(value = "/assistantManager/page")
    @PostMapping("/scheduledTaskAuthorize")
    public ResponseEntity<ResultMsg> scheduledTaskAuthorize(
            @RequestBody(required = false) Object payload) {
        if (payload != null && (!(payload instanceof Map) || !((Map<?, ?>) payload).isEmpty()))
            return unauthorized();
        Map<String, Object> context = AgentAuthorizationInterceptor.currentContext();
        if (context == null) return unauthorized();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(ResultMsg.ok(context));
    }

    private static ResponseEntity<ResultMsg> unauthorized() {
        return ResponseEntity.status(401).header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(ResultMsg.fail("scheduled_auth_error"));
    }

    /** Lift service status into the envelope; keep only business fields in data. */
    private static ResultMsg result(Map<String, Object> response) {
        Map<String, Object> data = new LinkedHashMap<>(response);
        boolean success = Boolean.TRUE.equals(data.remove("success"));
        String errorMsg = (String) data.remove("errorMsg");
        ResultMsg result = success ? ResultMsg.ok(null) : ResultMsg.fail(errorMsg);
        result.setData(data.containsKey("data") ? data.get("data") : data);
        return result;
    }

    @AgentPermission(value = "/assistantManager/page")
    @PostMapping("/getConversationMessages")
    public ResultMsg messages(
            @RequestBody Map<String, Object> payload) {
        return result(conversations.conversationMessages(json.<ObjectNode>valueToTree(payload).put(USER_ID, userId()).toString()));
    }

    @AgentPermission(value = "/assistantManager/page")
    @PostMapping("/completeRun")
    public ResultMsg completeRun(
            @RequestBody Map<String, Object> payload) {
        return result(executions.completeRun(json.<ObjectNode>valueToTree(payload).put(USER_ID, userId()).toString()));
    }

    @AgentPermission(value = "/assistantManager/page")
    @PostMapping("/memoryStore/read")
    public ResultMsg memoryRead(
            @RequestBody Map<String, Object> payload) {
        return result(memoryStore.memoryRead(json.<ObjectNode>valueToTree(payload).put(USER_ID, userId()).toString()));
    }

    @AgentPermission(value = "/assistantManager/page")
    @PostMapping("/memoryStore/list")
    public ResultMsg memoryList(
            @RequestBody Map<String, Object> payload) {
        return result(memoryStore.memoryList(json.<ObjectNode>valueToTree(payload).put(USER_ID, userId()).toString()));
    }

    @AgentPermission(value = "/assistantManager/page")
    @PostMapping("/memoryStore/operation")
    public ResultMsg memoryOperation(
            @RequestBody Map<String, Object> payload) {
        return result(memoryStore.memoryOperation(json.<ObjectNode>valueToTree(payload).put(USER_ID, userId()).toString()));
    }

    @AgentPermission(value = "/assistantManager/page")
    @PostMapping("/memoryStore/write")
    public ResultMsg memoryWrite(
            @RequestBody Map<String, Object> payload) {
        return result(memoryStore.memoryWrite(json.<ObjectNode>valueToTree(payload).put(USER_ID, userId()).toString()));
    }

    @AgentPermission(value = "/assistantManager/page")
    @PostMapping("/memoryStore/delete")
    public ResultMsg memoryDelete(
            @RequestBody Map<String, Object> payload) {
        return result(memoryStore.memoryDelete(json.<ObjectNode>valueToTree(payload).put(USER_ID, userId()).toString()));
    }

    @AgentPermission(value = "/assistantManager/page")
    @PostMapping("/memoryStore/search")
    public ResultMsg memorySearch(
            @RequestBody Map<String, Object> payload) {
        return result(memoryStore.memorySearch(json.<ObjectNode>valueToTree(payload).put(USER_ID, userId()).toString()));
    }

    @AgentPermission(value = "/assistantManager/page")
    @PostMapping("/getOrgInfo")
    public ResultMsg getOrgInfo(
            @RequestBody Map<String, Object> payload) {
        return result(runningAnalysis.getOrgInfo(json.valueToTree(payload).toString()));
    }

    @AgentPermission(value = "/assistantManager/page")
    @PostMapping("/queryBigData")
    public ResultMsg queryBigData(
            @RequestBody Map<String, Object> payload) {
        return result(runningAnalysis.queryBigData(json.valueToTree(payload).toString()));
    }

    @AgentPermission(value = "/assistantManager/page")
    @PostMapping("/queryChanges")
    public ResultMsg queryChanges(@RequestBody Map<String, Object> payload) {
        return result(runningAnalysis.queryChanges(json.valueToTree(payload).toString()));
    }

    @AgentPermission(value = "/assistantManager/page")
    @PostMapping("/queryFaults")
    public ResultMsg queryFaults(@RequestBody Map<String, Object> payload) {
        return result(runningAnalysis.queryFaults(json.valueToTree(payload).toString()));
    }

    // Only owner-scoped Agent APIs need an identity in their business input.
    private static String userId() {
        Map<String, Object> scheduled = AgentAuthorizationInterceptor.currentContext();
        return scheduled == null ? AuthContextHolder.getAuthUserDetails().getLoginName()
                : (String) scheduled.get(USER_ID);
    }

    @ExceptionHandler(AuthorizationException.class)
    public ResponseEntity<ResultMsg> permissionDenied() {
        return ResponseEntity.status(403).header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(ResultMsg.fail("agent_permission_denied"));
    }

    @ExceptionHandler(RunningAnalysisMockService.QueryException.class)
    public ResponseEntity<ResultMsg> queryError(RunningAnalysisMockService.QueryException error) {
        ResultMsg body = ResultMsg.fail("运维查询未执行，请核对条件及 mock 支持范围。");
        body.setData(java.util.Collections.singletonMap("errorCode", error.getCode()));
        return ResponseEntity.status(error.getStatus()).body(body);
    }

}
