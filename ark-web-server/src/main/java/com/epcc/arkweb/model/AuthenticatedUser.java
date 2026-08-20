package com.epcc.arkweb.model;

public final class AuthenticatedUser extends ShiroUser implements TrustedPrincipal {
    private final String userId;
    private final String orgCode;
    private final String roleId;
    private final String cookieHeader;

    public AuthenticatedUser(String userId, String orgCode, String roleId, String cookieHeader) {
        this.userId = userId;
        this.orgCode = orgCode;
        this.roleId = roleId;
        this.cookieHeader = cookieHeader;
        setLoginName(userId);
        setOrgCode(orgCode);
        setRoleId(roleId);
    }

    public String getAuthenticationType() { return "CAS"; }
    public String getUserId() { return userId; }
    public String getOrgCode() { return orgCode; }
    public String getRoleId() { return roleId; }
    public String getCookieHeader() { return cookieHeader; }

    @Override
    public String toString() { return "AuthenticatedUser{userId='" + userId + "', orgCode='" + orgCode + "'}"; }
}
