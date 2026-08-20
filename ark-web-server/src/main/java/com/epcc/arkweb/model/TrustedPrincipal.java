package com.epcc.arkweb.model;

public interface TrustedPrincipal {
    String getAuthenticationType();
    String getUserId();
    String getOrgCode();
}
