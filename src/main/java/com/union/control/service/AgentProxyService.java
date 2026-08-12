package com.union.control.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
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
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AgentProxyService {
    private static final Logger logger = LoggerFactory.getLogger(AgentProxyService.class);
    private final String pyAppBaseUrl;
    private final String behaviorRiskToken;
    private final RestTemplate http;

    @Autowired
    public AgentProxyService(
            @Value("${agent.py-app-base-url}") String pyAppBaseUrl,
            @Value("${agent.behavior-risk-token}") String behaviorRiskToken) {
        this(pyAppBaseUrl, behaviorRiskToken, new RestTemplate());
    }

    AgentProxyService(String pyAppBaseUrl, String behaviorRiskToken, RestTemplate http) {
        this.pyAppBaseUrl = pyAppBaseUrl.replaceAll("/+$", "");
        this.behaviorRiskToken = behaviorRiskToken;
        this.http = http;
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

    public ResponseEntity<byte[]> sync(String cookie, byte[] payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.COOKIE, cookie);
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
