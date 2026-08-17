package com.union.control.local.security;

import org.apache.shiro.authc.AuthenticationToken;

public final class CasSessionToken implements AuthenticationToken {
    private final String cookieHeader;

    public CasSessionToken(String cookieHeader) {
        this.cookieHeader = cookieHeader;
    }

    public Object getPrincipal() { return cookieHeader; }
    public Object getCredentials() { return cookieHeader; }
}
