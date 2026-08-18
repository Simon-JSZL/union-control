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
    private final RestTemplate http;

    @Autowired
    public AgentProxyService(
            @Value("${agent.py-app-base-url}") String pyAppBaseUrl) {
        this(pyAppBaseUrl, new RestTemplate());
    }

    public AgentProxyService(String pyAppBaseUrl, RestTemplate http) {
        this.pyAppBaseUrl = pyAppBaseUrl.replaceAll("/+$", "");
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

    public int stream(String cookie, byte[] payload, HttpServletResponse response) {
        long startedAt = System.nanoTime();
        logger.info("Agent call started mode=stream payload_bytes={}", payload.length);
        try {
            int status = http.execute(
                    pyAppBaseUrl + "/agent/v1/runs",
                    HttpMethod.POST,
                    request -> {
                        request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                        request.getHeaders().setAccept(java.util.Collections.singletonList(MediaType.TEXT_EVENT_STREAM));
                        request.getHeaders().set(HttpHeaders.COOKIE, cookie);
                        StreamUtils.copy(payload, request.getBody());
                    },
                    upstream -> {
                        int upstreamStatus = upstream.getRawStatusCode();
                        response.setStatus(upstreamStatus);
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
                        return upstreamStatus;
                    }
            );
            logCompleted("stream", status, startedAt);
            return status;
        } catch (RuntimeException error) {
            logFailed("stream", startedAt, error);
            throw error;
        }
    }

    public ResponseEntity<byte[]> sync(String cookie, byte[] payload) {
        return sync(cookie, payload, null, null);
    }

    public ResponseEntity<byte[]> sync(
            String cookie, byte[] payload, String effectiveAt, String effectiveTimezone) {
        long startedAt = System.nanoTime();
        logger.info("Agent call started mode=sync payload_bytes={}", payload.length);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.COOKIE, cookie);
        if (effectiveAt != null) headers.set("X-Agent-Effective-At", effectiveAt);
        if (effectiveTimezone != null)
            headers.set("X-Agent-Effective-Timezone", effectiveTimezone);
        try {
            ResponseEntity<byte[]> response = http.exchange(
                    pyAppBaseUrl + "/agent/v1/runs/sync",
                    HttpMethod.POST,
                    new HttpEntity<>(payload, headers),
                    byte[].class
            );
            logCompleted("sync", response.getStatusCode().value(), startedAt);
            return response;
        } catch (RuntimeException error) {
            logFailed("sync", startedAt, error);
            throw error;
        }
    }

    public ResponseEntity<byte[]> scheduled(String authorization) {
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
            return response;
        } catch (RuntimeException error) {
            logFailed("scheduled", startedAt, error);
            throw error;
        }
    }

    public void cancel(String cookie, String conversationId, String runId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.COOKIE, cookie);
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

    private static void copyHeader(ClientHttpResponse upstream, HttpServletResponse response, String name)
            throws IOException {
        String value = upstream.getHeaders().getFirst(name);
        if (value != null) response.setHeader(name, value);
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

    public static class ClientDisconnectedException extends RuntimeException {
        ClientDisconnectedException(IOException cause) {
            super(cause);
        }
    }
}
