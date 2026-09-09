package com.epcc.arkweb.web.llm;

import com.union.control.service.AgentProxyService;
import com.union.control.service.AgentResponse;
import com.union.control.service.AgentExecutionService;
import com.union.control.service.ConversationService;
import com.epcc.arkweb.helper.AuthenticatedRequest;
import org.apache.shiro.authz.annotation.RequiresPermissions;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Browser-facing LLM API. Only Agent runs cross the Python boundary. */
@Controller
@RequestMapping(value = {"llm", "union-op/llm"})
public class LlmController {
    private final ConversationService conversations;
    private final AgentExecutionService executions;
    private final AgentProxyService gateway;
    private final AuthenticatedRequest request;
    private final String pyAppBaseUrl;
    private final RestTemplate http;

    @Autowired
    public LlmController(
            ConversationService conversations,
            AgentExecutionService executions,
            AgentProxyService gateway,
            AuthenticatedRequest request,
            @Value("${agent.py-app-base-url}") String pyAppBaseUrl,
            @Value("${AGENT_MAX_RUN_SECONDS:900}") double maxRunSeconds) {
        this(conversations, executions, gateway, request, pyAppBaseUrl, new RestTemplate());
        double millis = Math.ceil(maxRunSeconds * 1000);
        if (!Double.isFinite(millis) || millis < 1 || millis > Integer.MAX_VALUE)
            throw new IllegalArgumentException("AGENT_MAX_RUN_SECONDS is out of range");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) millis);
        factory.setReadTimeout((int) millis);
        http.setRequestFactory(factory);
    }

    LlmController(
            ConversationService conversations,
            AgentExecutionService executions,
            AgentProxyService gateway,
            AuthenticatedRequest request,
            String pyAppBaseUrl,
            RestTemplate http) {
        this.conversations = conversations;
        this.executions = executions;
        this.gateway = gateway;
        this.request = request;
        this.pyAppBaseUrl = pyAppBaseUrl.replaceAll("/+$", "");
        this.http = http;
        this.http.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }

            @Override
            public void handleError(ClientHttpResponse response) {}
        });
    }

    @GetMapping("/conversationList")
    @RequiresPermissions(value = "/assistantManager/page")
    @ResponseBody
    public Map<String, Object> conversationList(
            @RequestParam(required = false, defaultValue = "100") int limit) {
        return conversations.conversations(request.json("limit", limit));
    }

    @GetMapping("/conversationDetails")
    @RequiresPermissions(value = "/assistantManager/page")
    @ResponseBody
    public Map<String, Object> conversationDetails(
            @RequestParam String conversationId) {
        return conversations.conversation(request.json("conversationId", conversationId));
    }

    @PostMapping("/conversationTitle")
    @RequiresPermissions(value = "/assistantManager/page")
    @ResponseBody
    public Map<String, Object> conversationTitle(
            @RequestBody Map<String, Object> payload) {
        return conversations.rename(request.json(payload));
    }

    @PostMapping("/conversationDelete")
    @RequiresPermissions(value = "/assistantManager/page")
    @ResponseBody
    public Map<String, Object> conversationDelete(
            @RequestBody Map<String, Object> payload) {
        return conversations.deleteConversation(request.json(payload));
    }

    @PostMapping("/executionCancel")
    @RequiresPermissions(value = "/assistantManager/page")
    @ResponseBody
    public ResponseEntity<String> executionCancel(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        Object conversationId = payload.get("conversationId");
        Object runId = payload.get("runId");
        if (!(conversationId instanceof String) || !(runId instanceof String))
            throw new IllegalArgumentException("缺少 execution 标识");
        return response(gateway.cancel(cookie, (String) conversationId, (String) runId));
    }

    @PostMapping(value = "/chatMessage", produces = "text/event-stream")
    @RequiresPermissions(value = "/assistantManager/page")
    public void chatMessage(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody byte[] payload, HttpServletResponse response) {
        byte[] clientPayload = request.clientPayload(payload);
        Map<String, Object> claimed = executions.claimAguiRun(request.json(clientPayload));
        @SuppressWarnings("unchecked")
        Map<String, Object> execution = (Map<String, Object>) claimed.get("data");
        String conversationId = String.valueOf(execution.get("conversationId"));
        String runId = String.valueOf(execution.get("runId"));
        AtomicBoolean disconnected = new AtomicBoolean();
        try {
            http.execute(pyAppBaseUrl + "/agent/v1/runs", HttpMethod.POST,
                    upstreamRequest -> {
                        upstreamRequest.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                        upstreamRequest.getHeaders().setAccept(
                                java.util.Collections.singletonList(MediaType.TEXT_EVENT_STREAM));
                        upstreamRequest.getHeaders().set(HttpHeaders.COOKIE, cookie);
                        upstreamRequest.getBody().write(clientPayload);
                    }, upstream -> {
                        response.setStatus(upstream.getRawStatusCode());
                        copyHeader(upstream, response, HttpHeaders.CONTENT_TYPE);
                        copyHeader(upstream, response, HttpHeaders.CACHE_CONTROL);
                        response.setHeader("X-Accel-Buffering", "no");
                        InputStream input = upstream.getBody();
                        byte[] buffer = new byte[4096];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            try {
                                response.getOutputStream().write(buffer, 0, read);
                                response.getOutputStream().flush();
                            } catch (IOException error) {
                                disconnected.set(true);
                                throw error;
                            }
                        }
                        try {
                            response.flushBuffer();
                        } catch (IOException error) {
                            disconnected.set(true);
                            throw error;
                        }
                        return null;
                    }
            );
        } catch (RuntimeException error) {
            if (!disconnected.get()) throw error;
            gateway.cancel(cookie, conversationId, runId);
        }
    }

    @PostMapping("/chatMessageSync")
    @RequiresPermissions(value = "/assistantManager/page")
    @ResponseBody
    public ResponseEntity<String> chatMessageSync(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody byte[] payload) {
        return response(gateway.sync(cookie,
                new String(request.clientPayload(payload), StandardCharsets.UTF_8)));
    }

    private static ResponseEntity<String> response(AgentResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.getStatus()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.getBody());
    }

    private static void copyHeader(
            ClientHttpResponse upstream, HttpServletResponse response, String name) {
        String value = upstream.getHeaders().getFirst(name);
        if (value != null) response.setHeader(name, value);
    }

}
