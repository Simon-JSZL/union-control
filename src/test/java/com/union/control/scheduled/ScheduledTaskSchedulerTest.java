package com.union.control.scheduled;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.service.DelegatedSessionService;
import com.union.control.service.LocalAuth;
import com.union.control.service.NonStreamRunService;
import org.junit.Test;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ScheduledTaskSchedulerTest {
    @Test
    public void enablingWithoutDelegatedSessionsFailsClosed() {
        DelegatedSessionService sessions = mock(DelegatedSessionService.class);
        assertThatThrownBy(() -> scheduler(mock(ScheduledTaskService.class),
                mock(NonStreamRunService.class), sessions, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("delegated session");
    }

    @Test
    public void scheduledRunUsesSharedSyncWithDelegatedCookieAndContextPayload() throws Exception {
        ScheduledTaskService service = mock(ScheduledTaskService.class);
        NonStreamRunService sync = mock(NonStreamRunService.class);
        DelegatedSessionService sessions = mock(DelegatedSessionService.class);
        when(sessions.isConfigured()).thenReturn(true);
        when(sessions.cookieForOwner(LocalAuth.USER_ID)).thenReturn(LocalAuth.cookieHeader());
        when(service.pendingRunIds(1)).thenReturn(Collections.singletonList(7L));
        when(service.beginRun(7L)).thenReturn(true);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("runId", 7L);
        context.put("taskId", 3L);
        context.put("userId", LocalAuth.USER_ID);
        context.put("prompt", "生成日报");
        context.put("scheduledAt", "2026-08-12T01:00:00Z");
        context.put("timezone", "Asia/Shanghai");
        when(service.executionContext(7L)).thenReturn(context);
        when(sync.run(eq(LocalAuth.cookieHeader()), any(byte[].class),
                eq("2026-08-12T01:00:00Z"), eq("Asia/Shanghai")))
                .thenReturn(ResponseEntity.ok("{\"content\":\"完成\"}".getBytes("UTF-8")));
        ScheduledTaskScheduler scheduler = scheduler(service, sync, sessions, true);
        try {
            scheduler.scan();
            org.mockito.ArgumentCaptor<byte[]> payload =
                    org.mockito.ArgumentCaptor.forClass(byte[].class);
            verify(sync, timeout(1000)).run(eq(LocalAuth.cookieHeader()), payload.capture(),
                    eq("2026-08-12T01:00:00Z"), eq("Asia/Shanghai"));
            @SuppressWarnings("unchecked") Map<String, Object> request =
                    new ObjectMapper().readValue(payload.getValue(), Map.class);
            assertThat(request).containsEntry("question", "生成日报");
            @SuppressWarnings("unchecked") Map<String, Object> input =
                    (Map<String, Object>) request.get("input");
            assertThat(input)
                    .containsEntry("scheduledRunId", 7)
                    .containsEntry("scheduledAt", "2026-08-12T01:00:00Z");
            verify(service, timeout(1000)).completeFromProxy(eq(7L), any(Map.class));
        } finally {
            scheduler.close();
        }
    }

    @Test
    public void doesNotSubmitTheSamePendingRunTwiceWhileBusy() throws Exception {
        ScheduledTaskService service = mock(ScheduledTaskService.class);
        when(service.pendingRunIds(1)).thenReturn(Collections.singletonList(7L));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(service.beginRun(7L)).thenAnswer(invocation -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return false;
        });
        ScheduledTaskScheduler scheduler = scheduler(service,
                mock(NonStreamRunService.class), configuredSessions(), true);
        try {
            scheduler.scan();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            scheduler.scan();
            verify(service, timeout(200).times(1)).beginRun(7L);
        } finally {
            release.countDown();
            scheduler.close();
        }
    }

    private static ScheduledTaskScheduler scheduler(
            ScheduledTaskService service, NonStreamRunService sync,
            DelegatedSessionService sessions, boolean enabled) {
        return new ScheduledTaskScheduler(service, sync, sessions, new ObjectMapper(),
                enabled, 1, 2, 1, 60);
    }

    private static DelegatedSessionService configuredSessions() {
        DelegatedSessionService sessions = mock(DelegatedSessionService.class);
        when(sessions.isConfigured()).thenReturn(true);
        return sessions;
    }
}
