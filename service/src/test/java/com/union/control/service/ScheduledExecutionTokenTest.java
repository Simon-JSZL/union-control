package com.union.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.mapper.ScheduledTaskMapper;
import com.union.control.security.ScheduledExecutionRealm;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ScheduledExecutionTokenTest {
    @Test
    public void tokenIs256BitBase64UrlAndHashesDeterministically() {
        ScheduledExecutionRealm.Grant first = ScheduledExecutionRealm.issue();
        ScheduledExecutionRealm.Grant second = ScheduledExecutionRealm.issue();
        String firstToken = first.authorizationHeader().substring("Scheduled ".length());
        String secondToken = second.authorizationHeader().substring("Scheduled ".length());

        assertThat(firstToken).hasSize(43).matches("[A-Za-z0-9_-]{43}")
                .isNotEqualTo(secondToken);
        assertThat(first.hash()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(first.toString()).doesNotContain(firstToken);
    }

    @Test
    public void beginRunPersistsOnlyTheTokenHash() {
        ScheduledTaskMapper mapper = mock(ScheduledTaskMapper.class);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("userId", "owner-1");
        context.put("orgCode", "org-1");
        context.put("roleId", "role-1");
        when(mapper.findPendingRunContext(7L)).thenReturn(context);
        when(mapper.beginRun(eq(7L), org.mockito.Matchers.anyString(),
                org.mockito.Matchers.anyString())).thenReturn(1);
        ScheduledTaskService service = new ScheduledTaskService(mapper, new ObjectMapper(),
                mock(ConversationService.class), 960, 930);

        ScheduledExecutionRealm.Grant grant = service.beginRun(7L);

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(mapper).beginRun(eq(7L), hash.capture(), org.mockito.Matchers.anyString());
        assertThat(hash.getValue()).matches("[0-9a-f]{64}");
        assertThat(grant.authorizationHeader()).startsWith("Scheduled ");
        assertThat(hash.getValue()).isNotEqualTo(grant.authorizationHeader().substring(10));
    }

    @Test
    public void missingProductionIdentityDoesNotClaimRun() {
        ScheduledTaskMapper mapper = mock(ScheduledTaskMapper.class);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("userId", "owner-1");
        context.put("orgCode", "org-1");
        when(mapper.findPendingRunContext(7L)).thenReturn(context);
        ScheduledTaskService service = new ScheduledTaskService(mapper, new ObjectMapper(),
                mock(ConversationService.class), 960, 930);

        assertThatThrownBy(() -> service.beginRun(7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("roleId");
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).beginRun(
                org.mockito.Matchers.anyLong(), org.mockito.Matchers.anyString(),
                org.mockito.Matchers.anyString());
    }
}
