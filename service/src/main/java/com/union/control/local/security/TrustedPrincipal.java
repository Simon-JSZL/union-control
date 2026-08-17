package com.union.control.local.security;

public interface TrustedPrincipal {
    String getAuthenticationType();
    String getUserId();
    String getOrgCode();
}
