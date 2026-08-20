package com.epcc.arkweb.config;

import com.union.control.mapper.ScheduledTaskMapper;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.junit.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ScheduledExecutionRealmTest {
    @Test
    public void restoresProductionShiroIdentityFromTheRunningOccurrence() {
        ScheduledTaskMapper mapper = mock(ScheduledTaskMapper.class);
        ScheduledExecutionRealm.Grant grant = ScheduledExecutionRealm.issue();
        when(mapper.findScheduledIdentity(grant.hash()))
                .thenReturn(identity(grant, Instant.now().plusSeconds(60)));
        ScheduledExecutionRealm realm = new ScheduledExecutionRealm(mapper);

        AuthenticationInfo info = realm.getAuthenticationInfo(token(grant));
        ScheduledExecutionRealm.Principal principal =
                (ScheduledExecutionRealm.Principal) info.getPrincipals().getPrimaryPrincipal();

        assertThat(principal.getLoginName()).isEqualTo("owner-1");
        assertThat(principal.getOrgCode()).isEqualTo("org-1");
        assertThat(principal.getRoleId()).isEqualTo("role-1");
        assertThat(principal.toString()).doesNotContain(grant.authorizationHeader())
                .doesNotContain(grant.hash());
    }

    @Test
    public void scheduledRealmDoesNotDuplicateProductionAuthorization() {
        ScheduledTaskMapper mapper = mock(ScheduledTaskMapper.class);
        ScheduledExecutionRealm.Grant grant = ScheduledExecutionRealm.issue();
        when(mapper.findScheduledIdentity(grant.hash()))
                .thenReturn(identity(grant, Instant.now().plusSeconds(60)));
        ScheduledExecutionRealm realm = new ScheduledExecutionRealm(mapper);
        ScheduledExecutionRealm.Principal principal =
                (ScheduledExecutionRealm.Principal) realm.getAuthenticationInfo(token(grant))
                        .getPrincipals().getPrimaryPrincipal();
        SimplePrincipalCollection principals = new SimplePrincipalCollection(
                principal, realm.getName());

        assertThat(realm.isPermitted(principals, "/assistantManager/page")).isFalse();
        assertThat(new LocalCasRealm().isPermitted(principals, "/assistantManager/page")).isTrue();
    }

    @Test
    public void missingExpiredOrMalformedDatabaseIdentityFailsAuthentication() {
        ScheduledTaskMapper mapper = mock(ScheduledTaskMapper.class);
        ScheduledExecutionRealm realm = new ScheduledExecutionRealm(mapper);
        ScheduledExecutionRealm.Grant missing = ScheduledExecutionRealm.issue();
        assertThatThrownBy(() -> realm.getAuthenticationInfo(token(missing)))
                .isInstanceOf(AuthenticationException.class);

        ScheduledExecutionRealm.Grant expired = ScheduledExecutionRealm.issue();
        when(mapper.findScheduledIdentity(expired.hash()))
                .thenReturn(identity(expired, Instant.now().minusSeconds(1)));
        assertThatThrownBy(() -> realm.getAuthenticationInfo(token(expired)))
                .isInstanceOf(AuthenticationException.class);

        ScheduledExecutionRealm.Grant malformed = ScheduledExecutionRealm.issue();
        Map<String, Object> invalid = identity(malformed, Instant.now().plusSeconds(60));
        invalid.remove("roleId");
        when(mapper.findScheduledIdentity(malformed.hash())).thenReturn(invalid);
        assertThatThrownBy(() -> realm.getAuthenticationInfo(token(malformed)))
                .isInstanceOf(AuthenticationException.class);
    }

    private static ScheduledExecutionRealm.Token token(ScheduledExecutionRealm.Grant grant) {
        return new ScheduledExecutionRealm.Token(
                grant.authorizationHeader().substring("Scheduled ".length()));
    }

    private static Map<String, Object> identity(
            ScheduledExecutionRealm.Grant grant, Instant expiresAt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("runId", 7L);
        row.put("taskId", 3L);
        row.put("userId", "owner-1");
        row.put("orgCode", "org-1");
        row.put("roleId", "role-1");
        row.put("prompt", "生成日报");
        row.put("timezone", "Asia/Shanghai");
        row.put("scheduledAt", "2026-08-13T01:00:00Z");
        row.put("expiresAt", expiresAt.toString());
        row.put("tokenHash", grant.hash());
        return row;
    }
}
