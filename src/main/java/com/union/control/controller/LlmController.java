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
            @RequestParam(required = false, defaultValue = "true") boolean includeArchived,
            @RequestParam(required = false, defaultValue = "100") int limit) {
        return service.conversations(LocalAuth.cookieHeader(), includeArchived, limit);
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

    @PostMapping("/llm/conversationRestore")
    @ResponseBody
    public Map<String, Object> conversationRestore(@RequestBody Map<String, Object> payload) {
        return service.restore(LocalAuth.cookieHeader(), payload);
    }

    @PostMapping("/llm/conversationExpire")
    @ResponseBody
    public Map<String, Object> conversationExpire(@RequestBody Map<String, Object> payload) {
        return service.expireConversation(LocalAuth.cookieHeader(), payload);
    }

    @GetMapping("/llm/executionCurrent")
    @ResponseBody
    public Map<String, Object> executionCurrent() {
        return service.currentExecution(LocalAuth.cookieHeader());
    }

    @PostMapping("/llm/executionCancel")
    @ResponseBody
    public Map<String, Object> executionCancel() {
        return service.cancelExecution(LocalAuth.cookieHeader());
    }

    @PostMapping(value = "/llm/chatMessage", produces = "text/event-stream")
    public void chatMessage(@RequestBody byte[] payload, HttpServletResponse response) {
        proxy.stream(payload, response);
    }

    @PostMapping("/llm/chatMessageSync")
    @ResponseBody
    public ResponseEntity<byte[]> chatMessageSync(@RequestBody byte[] payload) {
        return proxy.sync(payload);
    }

}
