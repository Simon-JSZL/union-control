package com.union.control.service;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/** Shared application entrypoint for every synchronous Agent run. */
@Service
public class NonStreamRunService {
    private final AgentProxyService proxy;

    public NonStreamRunService(AgentProxyService proxy) {
        this.proxy = proxy;
    }

    public ResponseEntity<byte[]> run(String cookie, byte[] payload) {
        return proxy.sync(LocalAuth.authenticatedCookieHeader(cookie), payload);
    }
}
