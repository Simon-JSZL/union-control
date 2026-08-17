package com.epcc.arkweb.web.llm;

import com.union.control.service.AgentProxyService;
import com.union.control.service.AgentExecutionService;
import com.union.control.service.ConversationService;
import org.apache.shiro.authz.annotation.RequiresPermissions;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletResponse;
import java.util.Map;

/** Browser-facing LLM API. Only Agent runs cross the Python boundary. */
@Controller
@RequestMapping(value = {"llm", "union-op/llm"})
@RequiresPermissions(value = "/assistantManager/page")
public class LlmController {
    private final ConversationService conversations;
    private final AgentExecutionService executions;
    private final AgentProxyService gateway;

    public LlmController(
            ConversationService conversations,
            AgentExecutionService executions,
            AgentProxyService gateway) {
        this.conversations = conversations;
        this.executions = executions;
        this.gateway = gateway;
    }

    @GetMapping("/conversationList")
    @ResponseBody
    public Map<String, Object> conversationList(
            @RequestParam(required = false, defaultValue = "100") int limit) {
        return conversations.conversations(limit);
    }

    @GetMapping("/conversationDetails")
    @ResponseBody
    public Map<String, Object> conversationDetails(
            @RequestParam String conversationId) {
        return conversations.conversation(conversationId);
    }

    @PostMapping("/conversationTitle")
    @ResponseBody
    public Map<String, Object> conversationTitle(
            @RequestBody Map<String, Object> payload) {
        return conversations.rename(payload);
    }

    @PostMapping("/conversationDelete")
    @ResponseBody
    public Map<String, Object> conversationDelete(
            @RequestBody Map<String, Object> payload) {
        return conversations.deleteConversation(payload);
    }

    @PostMapping("/executionCancel")
    @ResponseBody
    public Map<String, Object> executionCancel(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        Map<String, Object> response = executions.cancelExecution(payload);
        Object raw = response.get("data");
        if (raw instanceof Map) {
            Map<?, ?> active = (Map<?, ?>) raw;
            Object conversationId = active.get("conversationId");
            Object runId = active.get("runId");
            if (conversationId instanceof String && runId instanceof String) {
                gateway.cancel(cookie, (String) conversationId, (String) runId);
            }
        }
        return response;
    }

    @PostMapping(value = "/chatMessage", produces = "text/event-stream")
    public void chatMessage(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody byte[] payload, HttpServletResponse response) {
        Map<String, Object> claimed = executions.claimAguiRun(payload);
        @SuppressWarnings("unchecked")
        Map<String, Object> execution = (Map<String, Object>) claimed.get("data");
        String conversationId = String.valueOf(execution.get("conversationId"));
        String runId = String.valueOf(execution.get("runId"));
        int status;
        try {
            status = gateway.stream(cookie, payload, response);
        } catch (AgentProxyService.ClientDisconnectedException error) {
            executions.cancelExecution(
                    conversationId, runId, "client_disconnected");
            gateway.cancel(cookie, conversationId, runId);
            return;
        } catch (RuntimeException error) {
            executions.failExecution(conversationId, runId, "agent_proxy_failed");
            throw error;
        }
        if (status >= 400)
            executions.failExecution(conversationId, runId, "agent_start_failed");
    }

    @PostMapping("/chatMessageSync")
    @ResponseBody
    public ResponseEntity<byte[]> chatMessageSync(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody byte[] payload) {
        return gateway.sync(cookie, payload);
    }

}
