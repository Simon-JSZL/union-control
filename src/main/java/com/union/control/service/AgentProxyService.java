package com.union.control.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AgentProxyService {
    private static final Logger logger = LoggerFactory.getLogger(AgentProxyService.class);
    private final String pyAppBaseUrl;
    private final String behaviorRiskToken;
    private final String scheduledTaskToken;
    private final ObjectMapper json;
    private final RestTemplate http;
    private final RestTemplate scheduledHttp;

    @Autowired
    public AgentProxyService(
            @Value("${agent.py-app-base-url}") String pyAppBaseUrl,
            @Value("${agent.behavior-risk-token}") String behaviorRiskToken,
            @Value("${agent.scheduled-task-token:}") String scheduledTaskToken,
            @Value("${agent.scheduled-connect-timeout-ms:5000}") int scheduledConnectTimeoutMs,
            @Value("${agent.scheduled-read-timeout-ms:930000}") int scheduledReadTimeoutMs,
            ObjectMapper json) {
        this(pyAppBaseUrl, behaviorRiskToken, scheduledTaskToken, json,
                new RestTemplate(), restTemplate(scheduledConnectTimeoutMs, scheduledReadTimeoutMs));
    }

    AgentProxyService(String pyAppBaseUrl, String behaviorRiskToken, RestTemplate http) {
        this(pyAppBaseUrl, behaviorRiskToken, "", new ObjectMapper(), http, http);
    }

    AgentProxyService(String pyAppBaseUrl, String behaviorRiskToken, String scheduledTaskToken,
                      ObjectMapper json, RestTemplate http, RestTemplate scheduledHttp) {
        this.pyAppBaseUrl = pyAppBaseUrl.replaceAll("/+$", "");
        this.behaviorRiskToken = behaviorRiskToken;
        this.scheduledTaskToken = scheduledTaskToken == null ? "" : scheduledTaskToken;
        this.json = json;
        this.http = http;
        this.scheduledHttp = scheduledHttp;
        ResponseErrorHandler passThroughErrors = new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }

            @Override
            public void handleError(ClientHttpResponse response) {}
        };
        this.http.setErrorHandler(passThroughErrors);
        this.scheduledHttp.setErrorHandler(passThroughErrors);
    }

    public int stream(byte[] payload, HttpServletResponse response) {
        return http.execute(
                pyAppBaseUrl + "/agent/v1/runs",
                HttpMethod.POST,
                request -> {
                    request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    request.getHeaders().setAccept(java.util.Collections.singletonList(MediaType.TEXT_EVENT_STREAM));
                    request.getHeaders().set(HttpHeaders.COOKIE, LocalAuth.cookieHeader());
                    StreamUtils.copy(payload, request.getBody());
                },
                upstream -> {
                    int status = upstream.getRawStatusCode();
                    response.setStatus(status);
                    copyHeader(upstream, response, HttpHeaders.CONTENT_TYPE);
                    copyHeader(upstream, response, HttpHeaders.CACHE_CONTROL);
                    copyHeader(upstream, response, "X-Accel-Buffering");
                    response.setHeader("X-Accel-Buffering", "no");
                    InputStream input = upstream.getBody();
                    OutputStream output = response.getOutputStream();
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        try {
                            output.write(buffer, 0, read);
                            output.flush();
                        } catch (IOException error) {
                            throw new ClientDisconnectedException(error);
                        }
                    }
                    try {
                        response.flushBuffer();
                    } catch (IOException error) {
                        throw new ClientDisconnectedException(error);
                    }
                    return status;
                }
        );
    }

    public ResponseEntity<byte[]> sync(byte[] payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.COOKIE, LocalAuth.cookieHeader());
        return http.exchange(
                pyAppBaseUrl + "/agent/v1/runs/sync",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                byte[].class
        );
    }

    public ResponseEntity<byte[]> behaviorRisk(byte[] payload) {
        return fixedScenario(
                "/agent/v1/scenarios/behavior-risk/runs",
                payload,
                behaviorRiskToken);
    }

    public Map<String, Object> draftScheduledTask(String cookie, byte[] payload) {
        HttpHeaders headers = jsonHeaders();
        headers.set(HttpHeaders.COOKIE, cookie);
        return scheduledExchange(
                "/agent/v1/scenarios/scheduled-task-draft/runs", payload, headers);
    }

    public Map<String, Object> executeScheduledTask(long runId) {
        if (!isScheduledTaskConfigured())
            throw new IllegalStateException("SCHEDULED_TASK_TOKEN 未配置");
        HttpHeaders headers = jsonHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + scheduledTaskToken);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId);
        try {
            return scheduledExchange(
                    "/agent/v1/scenarios/scheduled-task/runs",
                    json.writeValueAsBytes(body), headers);
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("定时任务请求序列化失败", error);
        }
    }

    public boolean isScheduledTaskConfigured() {
        return !scheduledTaskToken.trim().isEmpty();
    }

    public void cancel(String conversationId, String runId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.COOKIE, LocalAuth.cookieHeader());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversationId", conversationId);
        body.put("runId", runId);
        try {
            http.exchange(
                    pyAppBaseUrl + "/agent/v1/runs/cancel",
                    HttpMethod.POST,
                    new HttpEntity<Map<String, Object>>(body, headers),
                    byte[].class
            );
        } catch (RestClientException error) {
            logger.warn("Agent cancel delivery failed conversation_id={} run_id={}",
                    conversationId, runId);
        }
    }

    private ResponseEntity<byte[]> fixedScenario(String path, byte[] payload, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return http.exchange(
                pyAppBaseUrl + path,
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                byte[].class
        );
    }

    private Map<String, Object> scheduledExchange(
            String path, byte[] body, HttpHeaders headers) {
        ResponseEntity<byte[]> response = scheduledHttp.exchange(
                pyAppBaseUrl + path, HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);
        if (!response.getStatusCode().is2xxSuccessful())
            throw new IllegalStateException(
                    "py-app 返回状态 " + response.getStatusCodeValue());
        try {
            byte[] bytes = response.getBody();
            if (bytes == null || bytes.length == 0) return Collections.emptyMap();
            Map<String, Object> parsed = json.readValue(
                    bytes, new TypeReference<Map<String, Object>>() {});
            if (parsed.containsKey("success")) {
                if (!Boolean.TRUE.equals(parsed.get("success")))
                    throw new IllegalStateException("py-app 业务请求失败");
                Object data = parsed.get("data");
                if (!(data instanceof Map))
                    throw new IllegalStateException("py-app 返回无效 JSON");
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) data;
                return result;
            }
            return parsed;
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("py-app 返回无效 JSON", error);
        }
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        return headers;
    }

    private static RestTemplate restTemplate(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }

    private static void copyHeader(ClientHttpResponse upstream, HttpServletResponse response, String name)
            throws IOException {
        String value = upstream.getHeaders().getFirst(name);
        if (value != null) response.setHeader(name, value);
    }

    public static class ClientDisconnectedException extends RuntimeException {
        ClientDisconnectedException(IOException cause) {
            super(cause);
        }
    }
}
