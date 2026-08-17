package com.union.control.scheduled;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.service.AgentProxyService;
import com.union.control.service.ScheduledTaskService;
import com.union.control.security.ScheduledExecutionRealm;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ScheduledTaskSchedulerTest {
    @Test
    public void scheduledRunDoesNotFabricateCookieFromTaskOwner() throws Exception {
        ScheduledTaskService service = mock(ScheduledTaskService.class);
        AgentProxyService sync = mock(AgentProxyService.class);
        CountDownLatch rejected = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        doAnswer(invocation -> {
            failed.countDown();
            return null;
        }).when(service).failRun(7L, "scheduled_run_failed", "定时任务执行失败");
        when(service.pendingRunIds(20)).thenReturn(fullBatch());
        ScheduledExecutionRealm.Grant grant = ScheduledExecutionRealm.issue();
        when(service.beginRun(7L)).thenReturn(grant);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("runId", 7L);
        context.put("taskId", 3L);
        context.put("userId", "owner-1");
        context.put("prompt", "生成日报");
        context.put("scheduledAt", "2026-08-12T01:00:00Z");
        context.put("timezone", "Asia/Shanghai");
        when(service.executionContext(7L)).thenReturn(context);
        when(sync.scheduled(any(String.class)))
                .thenAnswer(invocation -> {
                    rejected.countDown();
                    throw new com.union.control.service.ServiceExceptions.UnauthorizedException();
                });
        ScheduledTaskScheduler scheduler = scheduler(service, sync, true);
        try {
            scheduler.scan();
            assertThat(rejected.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(failed.await(2, TimeUnit.SECONDS)).isTrue();
            verify(sync).scheduled(eq(grant.authorizationHeader()));
            verify(service).failRun(
                    7L, "scheduled_run_failed", "定时任务执行失败");
        } finally {
            scheduler.close();
        }
    }

    @Test
    public void doesNotSubmitTheSamePendingRunTwiceWhileBusy() throws Exception {
        ScheduledTaskService service = mock(ScheduledTaskService.class);
        when(service.pendingRunIds(20)).thenReturn(fullBatch());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(service.beginRun(7L)).thenAnswer(invocation -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return null;
        });
        ScheduledTaskScheduler scheduler = scheduler(service,
                mock(AgentProxyService.class), true);
        try {
            scheduler.scan();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            scheduler.scan();
            verify(service).beginRun(7L);
        } finally {
            release.countDown();
            scheduler.close();
        }
    }

    private static ScheduledTaskScheduler scheduler(
            ScheduledTaskService service, AgentProxyService sync, boolean enabled) {
        return new ScheduledTaskScheduler(service, sync, new ObjectMapper(),
                enabled, 1, 60);
    }

    private static List<Long> fullBatch() {
        List<Long> runIds = new ArrayList<>();
        for (long runId = 7; runId < 27; runId++) runIds.add(runId);
        return runIds;
    }
}
