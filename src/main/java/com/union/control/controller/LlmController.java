package com.union.control.controller;

import com.union.control.service.AgentProxyService;
import com.union.control.service.ControlService;
import com.union.control.service.LocalAuth;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletResponse;
import java.util.Map;

/** Browser-facing LLM API. Only Agent runs cross the Python boundary. */
@Controller
public class LlmController {
    private final ControlService service;
    private final AgentProxyService proxy;

    public LlmController(ControlService service, AgentProxyService proxy) {
        this.service = service;
        this.proxy = proxy;
    }

    @GetMapping("/llm/conversationList")
    @ResponseBody
    public Map<String, Object> conversationList(
            @RequestParam(required = false, defaultValue = "100") int limit) {
        return service.conversations(LocalAuth.cookieHeader(), limit);
    }

    @GetMapping("/llm/conversationDetails")
    @ResponseBody
    public Map<String, Object> conversationDetails(@RequestParam String conversationId) {
        return service.conversation(LocalAuth.cookieHeader(), conversationId);
    }

    @PostMapping("/llm/conversationTitle")
    @ResponseBody
    public Map<String, Object> conversationTitle(@RequestBody Map<String, Object> payload) {
        return service.rename(LocalAuth.cookieHeader(), payload);
    }

    @PostMapping("/llm/conversationDelete")
    @ResponseBody
    public Map<String, Object> conversationDelete(@RequestBody Map<String, Object> payload) {
        return service.deleteConversation(LocalAuth.cookieHeader(), payload);
    }

    @PostMapping("/llm/executionCancel")
    @ResponseBody
    public Map<String, Object> executionCancel(@RequestBody Map<String, Object> payload) {
        Map<String, Object> response = service.cancelExecution(
                LocalAuth.cookieHeader(), payload);
        Object raw = response.get("data");
        if (raw instanceof Map) {
            Map<?, ?> active = (Map<?, ?>) raw;
            Object conversationId = active.get("conversationId");
            Object runId = active.get("runId");
            if (conversationId instanceof String && runId instanceof String) {
                proxy.cancel((String) conversationId, (String) runId);
            }
        }
        return response;
    }

    @PostMapping(value = "/llm/chatMessage", produces = "text/event-stream")
    public void chatMessage(@RequestBody byte[] payload, HttpServletResponse response) {
        String cookie = LocalAuth.cookieHeader();
        Map<String, Object> claimed = service.claimAguiRun(cookie, payload);
        @SuppressWarnings("unchecked")
        Map<String, Object> execution = (Map<String, Object>) claimed.get("data");
        String conversationId = String.valueOf(execution.get("conversationId"));
        String runId = String.valueOf(execution.get("runId"));
        int status;
        try {
            status = proxy.stream(payload, response);
        } catch (AgentProxyService.ClientDisconnectedException error) {
            service.cancelExecution(
                    cookie, conversationId, runId, "client_disconnected");
            proxy.cancel(conversationId, runId);
            return;
        } catch (RuntimeException error) {
            service.failExecution(cookie, conversationId, runId, "agent_proxy_failed");
            throw error;
        }
        if (status >= 400)
            service.failExecution(cookie, conversationId, runId, "agent_start_failed");
    }

    @PostMapping("/llm/chatMessageSync")
    @ResponseBody
    public ResponseEntity<byte[]> chatMessageSync(@RequestBody byte[] payload) {
        return proxy.sync(payload);
    }

    @PostMapping("/llm/behaviorRisk")
    @ResponseBody
    public ResponseEntity<byte[]> behaviorRisk(@RequestBody byte[] payload) {
        return proxy.behaviorRisk(payload);
    }

}
