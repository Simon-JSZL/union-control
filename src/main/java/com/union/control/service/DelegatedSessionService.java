package com.union.control.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Local-only delegated-session adapter. Production must replace this service with an
 * authentication-service-approved exchange and must leave the mock disabled.
 */
@Service
public class DelegatedSessionService {
    private final boolean localMockEnabled;

    public DelegatedSessionService(
            @Value("${agent.scheduled-local-delegated-session-enabled:false}")
            boolean localMockEnabled) {
        this.localMockEnabled = localMockEnabled;
    }

    public boolean isConfigured() {
        return localMockEnabled;
    }

    public String cookieForOwner(String userId) {
        if (!localMockEnabled) throw new ControlService.UnauthorizedException();
        return LocalAuth.cookieHeaderForUser(userId);
    }
}
