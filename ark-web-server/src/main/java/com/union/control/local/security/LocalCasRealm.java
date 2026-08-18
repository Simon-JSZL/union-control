package com.union.control.local.security;

import com.epcc.arkweb.model.ShiroUser;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;

/** Local auth-service adapter. Replace this realm's lookup in production. */
public class LocalCasRealm extends AuthorizingRealm {
    public static final String CAS_SESSION_ID = "session-1";
    public static final String USER_ID = "user-1";
    public static final String ORG_CODE = "104100000004";
    public static final String ROLE_ID = "ark-role-1";
    public static final String AGENT_EXECUTE = "agent:execute";
    public static final String ASSISTANT_MANAGER = "/assistantManager/page";

    public static String cookieHeader() {
        return "CASSESSIONID=" + CAS_SESSION_ID;
    }

    public boolean supports(AuthenticationToken token) {
        return token instanceof CasSessionToken;
    }

    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) {
        String cookie = String.valueOf(token.getCredentials());
        try {
            AuthenticatedUser user = new AuthenticatedUser(
                    authenticate(cookie), ORG_CODE, ROLE_ID, cookieHeader());
            return new SimpleAuthenticationInfo(user, token.getCredentials(), getName());
        } catch (RuntimeException error) {
            throw new AuthenticationException("Invalid CAS session", error);
        }
    }

    private static String authenticate(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.length() > 8192
                || cookieHeader.indexOf('\r') >= 0 || cookieHeader.indexOf('\n') >= 0)
            throw new AuthenticationException("Invalid CAS session");
        String cas = null;
        for (String raw : cookieHeader.split(";", -1)) {
            String part = raw.trim();
            if (part.isEmpty()) continue;
            int separator = part.indexOf('=');
            if (separator < 1) throw new AuthenticationException("Invalid CAS session");
            if ("CASSESSIONID".equals(part.substring(0, separator).trim())) {
                if (cas != null) throw new AuthenticationException("Invalid CAS session");
                cas = part.substring(separator + 1).trim();
            }
        }
        if (!CAS_SESSION_ID.equals(cas)) throw new AuthenticationException("Invalid CAS session");
        return USER_ID;
    }

    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
        SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();
        if (principals.oneByType(ShiroUser.class) != null)
            info.setStringPermissions(new java.util.LinkedHashSet<>(java.util.Arrays.asList(
                    AGENT_EXECUTE, ASSISTANT_MANAGER)));
        return info;
    }
}
