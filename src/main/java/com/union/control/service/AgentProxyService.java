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
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

@Service
public class AgentProxyService {
    private final String pyAppBaseUrl;
    private final String syncToken;
    private final RestTemplate http;

    @Autowired
    public AgentProxyService(
            @Value("${agent.py-app-base-url}") String pyAppBaseUrl,
            @Value("${agent.sync-token}") String syncToken) {
        this(pyAppBaseUrl, syncToken, new RestTemplate());
    }

    AgentProxyService(String pyAppBaseUrl, String syncToken, RestTemplate http) {
        this.pyAppBaseUrl = pyAppBaseUrl.replaceAll("/+$", "");
        this.syncToken = syncToken;
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

    public void stream(byte[] payload, HttpServletResponse response) {
        http.execute(
                pyAppBaseUrl + "/agent/v1/runs",
                HttpMethod.POST,
                request -> {
                    request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    request.getHeaders().setAccept(java.util.Collections.singletonList(MediaType.TEXT_EVENT_STREAM));
                    request.getHeaders().set(HttpHeaders.COOKIE, LocalAuth.cookieHeader());
                    StreamUtils.copy(payload, request.getBody());
                },
                upstream -> {
                    response.setStatus(upstream.getRawStatusCode());
                    copyHeader(upstream, response, HttpHeaders.CONTENT_TYPE);
                    copyHeader(upstream, response, HttpHeaders.CACHE_CONTROL);
                    copyHeader(upstream, response, "X-Accel-Buffering");
                    response.setHeader("X-Accel-Buffering", "no");
                    InputStream input = upstream.getBody();
                    OutputStream output = response.getOutputStream();
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                        output.flush();
                    }
                    response.flushBuffer();
                    return null;
                }
        );
    }

    public ResponseEntity<byte[]> sync(byte[] payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + syncToken);
        return http.exchange(
                pyAppBaseUrl + "/agent/v1/runs/sync",
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
}
