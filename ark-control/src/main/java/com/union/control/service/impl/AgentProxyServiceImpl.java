package com.union.control.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.service.AgentProxyService;
import com.union.control.service.AgentResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Service("agentProxyService")
public class AgentProxyServiceImpl implements AgentProxyService {
    private static final Logger logger = LoggerFactory.getLogger(AgentProxyServiceImpl.class);
    private final String pyAppBaseUrl;
    private final RestTemplate http;
    private final ObjectMapper json;

    @Autowired
    public AgentProxyServiceImpl(
            @Value("${agent.py-app-base-url}") String pyAppBaseUrl,
            ObjectMapper json) {
        this(pyAppBaseUrl, new RestTemplate(), json);
    }

    public AgentProxyServiceImpl(String pyAppBaseUrl, RestTemplate http, ObjectMapper json) {
        this.pyAppBaseUrl = pyAppBaseUrl.replaceAll("/+$", "");
        this.http = http;
        this.json = json;
        ResponseErrorHandler passThroughErrors = new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }

            @Override
            public void handleError(ClientHttpResponse response) {}
        };
        this.http.setErrorHandler(passThroughErrors);
    }

    public AgentResponse sync(String cookie, String payload) {
        long startedAt = System.nanoTime();
        logger.info("Agent call started mode=sync payload_chars={}", payload.length());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.COOKIE, cookie);
        try {
            ResponseEntity<byte[]> response = http.exchange(
                    pyAppBaseUrl + "/agent/v1/runs/sync",
                    HttpMethod.POST,
                    new HttpEntity<>(payload.getBytes(StandardCharsets.UTF_8), headers),
                    byte[].class
            );
            logCompleted("sync", response.getStatusCode().value(), startedAt);
            return result(response);
        } catch (RuntimeException error) {
            logFailed("sync", startedAt, error);
            throw error;
        }
    }

    public AgentResponse scheduled(String authorization) {
        long startedAt = System.nanoTime();
        logger.info("Agent call started mode=scheduled");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, authorization);
        try {
            ResponseEntity<byte[]> response = http.exchange(
                    pyAppBaseUrl + "/agent/v1/runs/scheduled",
                    HttpMethod.POST,
                    new HttpEntity<byte[]>(new byte[]{'{', '}'}, headers),
                    byte[].class);
            logCompleted("scheduled", response.getStatusCode().value(), startedAt);
            return result(response);
        } catch (RuntimeException error) {
            logFailed("scheduled", startedAt, error);
            throw error;
        }
    }

    public AgentResponse cancel(String cookie, String conversationId, String runId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.COOKIE, cookie);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversationId", conversationId);
        body.put("runId", runId);
        return result(http.exchange(
                pyAppBaseUrl + "/agent/v1/runs/cancel",
                HttpMethod.POST,
                new HttpEntity<Map<String, Object>>(body, headers),
                byte[].class
        ));
    }

    private AgentResponse result(ResponseEntity<byte[]> response) {
        try {
            String body = json.writeValueAsString(json.readTree(
                    new String(response.getBody(), StandardCharsets.UTF_8)));
            return new AgentResponse(response.getStatusCode().value(), body);
        } catch (Exception error) {
            throw new IllegalStateException("Agent 返回的不是合法 JSON", error);
        }
    }

    private static void logCompleted(String mode, int status, long startedAt) {
        long duration = (System.nanoTime() - startedAt) / 1_000_000L;
        if (status >= 400)
            logger.warn("Agent call completed mode={} status={} duration_ms={}", mode, status, duration);
        else
            logger.info("Agent call completed mode={} status={} duration_ms={}", mode, status, duration);
    }

    private static void logFailed(String mode, long startedAt, RuntimeException error) {
        logger.warn("Agent call failed mode={} duration_ms={} error_type={}", mode,
                (System.nanoTime() - startedAt) / 1_000_000L, error.getClass().getSimpleName());
    }

}
