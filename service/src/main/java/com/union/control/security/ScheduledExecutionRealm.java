package com.union.control.security;

import com.epcc.arkweb.model.ShiroUser;
import com.union.control.mapper.ScheduledTaskMapper;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.credential.CredentialsMatcher;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/** Authenticates one scheduled occurrence; production's existing Realm owns permissions. */
public final class ScheduledExecutionRealm extends AuthorizingRealm {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ScheduledTaskMapper mapper;

    public ScheduledExecutionRealm(ScheduledTaskMapper mapper) {
        this.mapper = mapper;
        setAuthenticationCachingEnabled(false);
        setAuthorizationCachingEnabled(false);
        setCredentialsMatcher(new ConstantTimeHashMatcher());
    }

    public static Grant issue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return new Grant(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    public boolean supports(AuthenticationToken token) {
        return token instanceof Token;
    }

    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) {
        try {
            Map<String, Object> row = mapper.findScheduledIdentity(
                    hash(String.valueOf(token.getCredentials())));
            if (row == null) throw invalid();
            Principal principal = new Principal(
                    number(row, "runId"), number(row, "taskId"), text(row, "userId", 64),
                    text(row, "orgCode", 64), text(row, "roleId", 64),
                    text(row, "prompt", 4096), Instant.parse(text(row, "scheduledAt", 64)),
                    text(row, "timezone", 64), Instant.parse(text(row, "expiresAt", 64)));
            if (!principal.getExpiresAt().isAfter(Instant.now())) throw invalid();
            return new SimpleAuthenticationInfo(
                    principal, String.valueOf(row.get("tokenHash")), getName());
        } catch (AuthenticationException error) {
            throw error;
        } catch (Exception error) {
            throw invalid();
        }
    }

    /** Scheduled auth does not duplicate production role-resource authorization. */
    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
        return new SimpleAuthorizationInfo();
    }

    private static long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (!(value instanceof Number) || ((Number) value).longValue() <= 0) throw invalid();
        return ((Number) value).longValue();
    }

    private static String text(Map<String, Object> row, String key, int max) {
        Object value = row.get(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()
                || ((String) value).length() > max)
            throw invalid();
        return (String) value;
    }

    private static boolean valid(String token) {
        if (token == null || token.length() != 43 || !token.matches("[A-Za-z0-9_-]{43}"))
            return false;
        try {
            return Base64.getUrlDecoder().decode(token).length == 32;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static String hash(String token) {
        if (!valid(token)) throw new IllegalArgumentException("Invalid scheduled token");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static AuthenticationException invalid() {
        return new AuthenticationException("Invalid scheduled credential");
    }

    static final class Token implements AuthenticationToken {
        private final char[] token;

        Token(String token) {
            if (!valid(token)) throw new IllegalArgumentException("Invalid scheduled token");
            this.token = token.toCharArray();
        }

        public Object getPrincipal() { return "scheduled-execution"; }
        public Object getCredentials() { return new String(token); }

        @Override
        public String toString() { return "ScheduledExecutionRealm.Token{credentials=[REDACTED]}"; }
    }

    private static final class ConstantTimeHashMatcher implements CredentialsMatcher {
        public boolean doCredentialsMatch(AuthenticationToken token, AuthenticationInfo info) {
            try {
                byte[] submitted = hash(String.valueOf(token.getCredentials()))
                        .getBytes(StandardCharsets.US_ASCII);
                byte[] stored = String.valueOf(info.getCredentials())
                        .getBytes(StandardCharsets.US_ASCII);
                return MessageDigest.isEqual(submitted, stored);
            } catch (RuntimeException error) {
                return false;
            }
        }
    }

    public static final class Grant {
        private final String token;

        private Grant(String token) { this.token = token; }
        public String authorizationHeader() { return "Scheduled " + token; }
        public String hash() { return ScheduledExecutionRealm.hash(token); }

        @Override
        public String toString() { return "ScheduledExecutionRealm.Grant{token=[REDACTED]}"; }
    }

    public static final class Principal extends ShiroUser {
        private static final long serialVersionUID = 1L;
        private final long runId;
        private final long taskId;
        private final String prompt;
        private final Instant scheduledAt;
        private final String timezone;
        private final Instant expiresAt;

        private Principal(long runId, long taskId, String userId, String orgCode, String roleId,
                String prompt, Instant scheduledAt, String timezone, Instant expiresAt) {
            this.runId = runId;
            this.taskId = taskId;
            this.prompt = prompt;
            this.scheduledAt = scheduledAt;
            this.timezone = timezone;
            this.expiresAt = expiresAt;
            setLoginName(userId);
            setOrgCode(orgCode);
            setRoleId(roleId);
        }

        public String getAuthenticationType() { return "SCHEDULED"; }
        public long getRunId() { return runId; }
        public long getTaskId() { return taskId; }
        public String getPrompt() { return prompt; }
        public Instant getScheduledAt() { return scheduledAt; }
        public String getTimezone() { return timezone; }
        public Instant getExpiresAt() { return expiresAt; }

        @Override
        public String toString() {
            return "ScheduledExecutionRealm.Principal{runId=" + runId + ", taskId=" + taskId
                    + ", userId='" + getLoginName() + "', orgCode='" + getOrgCode() + "'}";
        }
    }
}
