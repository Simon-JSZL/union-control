package com.union.control.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.service.AgentProxyService;
import com.union.control.service.AgentResponse;
import com.union.control.service.ScheduledTaskService;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ScheduledTaskSchedulerTest {
    @Test
    public void persistsSafeAgentFailureDetails() throws Exception {
        ScheduledTaskService service = mock(ScheduledTaskService.class);
        AgentProxyService proxy = mock(AgentProxyService.class);
        CountDownLatch failed = new CountDownLatch(1);
        when(service.pendingRunIds(20)).thenReturn(Collections.singletonList(28L));
        when(service.claimDueRun()).thenReturn(null);
        when(service.beginRun(eq(28L), anyString(), anyString()))
                .thenReturn(true);
        when(proxy.scheduled(anyString())).thenReturn(new AgentResponse(
                502,
                ("{\"error\":\"Tool resolve_member_org 重试耗尽：上游查询失败，HTTP 400。\","
                        + "\"code\":\"tool_retry_exhausted\"}")));
        doAnswer(invocation -> {
            failed.countDown();
            return null;
        }).when(service).failRun(28L, "tool_retry_exhausted",
                "Tool resolve_member_org 重试耗尽：上游查询失败，HTTP 400。");

        ScheduledTaskScheduler scheduler = new ScheduledTaskScheduler(
                service, proxy, new ObjectMapper(), true, 1, 930, 960);
        try {
            scheduler.scan();
            org.junit.Assert.assertTrue(failed.await(2, TimeUnit.SECONDS));
            verify(service).failRun(28L, "tool_retry_exhausted",
                    "Tool resolve_member_org 重试耗尽：上游查询失败，HTTP 400。");
        } finally {
            scheduler.close();
        }
    }

    @Test public void disabledAndRejectedScansDoNotExecuteModels() {
        ScheduledTaskService service=mock(ScheduledTaskService.class); AgentProxyService proxy=mock(AgentProxyService.class);
        when(service.claimDueRun()).thenReturn(null);
        ScheduledTaskScheduler disabled=new ScheduledTaskScheduler(service,proxy,new ObjectMapper(),false,1,930,960);
        disabled.scan(); disabled.close(); org.mockito.Mockito.verifyZeroInteractions(service,proxy);
        ScheduledTaskScheduler closed=new ScheduledTaskScheduler(service,proxy,new ObjectMapper(),true,1,930,960);
        when(service.pendingRunIds(20)).thenReturn(Collections.singletonList(7L));
        closed.close(); closed.scan(); org.mockito.Mockito.verifyZeroInteractions(proxy);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ScheduledTaskScheduler(service,proxy,new ObjectMapper(),true,1,930,930))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ScheduledTaskScheduler(service,proxy,new ObjectMapper(),true,1,930,3601))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test public void successfulPendingAndNewlyClaimedRunsCompleteThroughTheSharedProxy() throws Exception {
        ScheduledTaskService service=mock(ScheduledTaskService.class); AgentProxyService proxy=mock(AgentProxyService.class);
        when(service.claimDueRun()).thenReturn(null);
        when(service.pendingRunIds(20)).thenReturn(Collections.singletonList(7L));
        when(service.claimDueRun()).thenReturn(8L,null);
        when(service.beginRun(eq(7L),anyString(),anyString())).thenReturn(true);
        when(proxy.scheduled(anyString())).thenReturn(new AgentResponse(200,"{\"content\":\"report\"}"));
        ScheduledTaskScheduler scheduler=new ScheduledTaskScheduler(service,proxy,new ObjectMapper(),true,1,930,960);
        drain(scheduler);
        verify(service).completeFromProxy(7L,Collections.singletonMap("content","report"));
        verify(service).beginRun(eq(8L),anyString(),anyString());
        verify(proxy,org.mockito.Mockito.times(1)).scheduled(org.mockito.Matchers.matches("Scheduled [A-Za-z0-9_-]{43}"));
    }

    @Test public void malformedResponsesAndPersistenceFailuresRemainSafeFailures() throws Exception {
        for (String body:new String[]{null,"","bad json","{}","{\"content\":\" \"}"}) {
            ScheduledTaskService service=mock(ScheduledTaskService.class); AgentProxyService proxy=mock(AgentProxyService.class);
        when(service.claimDueRun()).thenReturn(null);
            when(service.pendingRunIds(20)).thenReturn(Collections.singletonList(7L));
            when(service.beginRun(eq(7L),anyString(),anyString())).thenReturn(true);
            when(proxy.scheduled(anyString())).thenReturn(new AgentResponse(200,body));
            if ("bad json".equals(body))
                org.mockito.Mockito.doThrow(new IllegalStateException("database unavailable")).when(service)
                        .failRun(7L,"scheduled_run_failed","定时任务执行失败");
            drain(new ScheduledTaskScheduler(service,proxy,new ObjectMapper(),true,1,930,960));
            verify(service).failRun(7L,"scheduled_run_failed","定时任务执行失败");
        }
        for (String body:new String[]{"bad json","{\"code\":12,\"error\":false}","{\"code\":\"bad code\",\"error\":\" \"}"}) {
            ScheduledTaskService service=mock(ScheduledTaskService.class); AgentProxyService proxy=mock(AgentProxyService.class);
        when(service.claimDueRun()).thenReturn(null);
            when(service.pendingRunIds(20)).thenReturn(Collections.singletonList(7L));
            when(service.beginRun(eq(7L),anyString(),anyString())).thenReturn(true);
            when(proxy.scheduled(anyString())).thenReturn(new AgentResponse(502,body));
            org.mockito.Mockito.doThrow(new IllegalStateException("database unavailable")).when(service)
                    .failRun(7L,"agent_http_error","Agent 服务返回 HTTP 502");
            drain(new ScheduledTaskScheduler(service,proxy,new ObjectMapper(),true,1,930,960));
            verify(service).failRun(7L,"agent_http_error","Agent 服务返回 HTTP 502");
        }
    }

    @Test public void alreadySubmittedRunsAreNotSubmittedAgain() throws Exception {
        ScheduledTaskService service=mock(ScheduledTaskService.class); AgentProxyService proxy=mock(AgentProxyService.class);
        when(service.claimDueRun()).thenReturn(null);
        when(service.pendingRunIds(20)).thenReturn(Collections.singletonList(7L));
        ScheduledTaskScheduler scheduler=new ScheduledTaskScheduler(service,proxy,new ObjectMapper(),true,1,930,960);
        ((java.util.Set<Long>)org.springframework.test.util.ReflectionTestUtils.getField(scheduler,"submitted")).add(7L);
        drain(scheduler);
        org.mockito.Mockito.verifyZeroInteractions(proxy);
        org.mockito.Mockito.verify(service,org.mockito.Mockito.never()).beginRun(eq(7L),anyString(),anyString());
    }

    private static void drain(ScheduledTaskScheduler scheduler) throws Exception {
        try {
            scheduler.scan();
            java.util.concurrent.ThreadPoolExecutor workers=(java.util.concurrent.ThreadPoolExecutor)
                    org.springframework.test.util.ReflectionTestUtils.getField(scheduler,"workers");
            workers.shutdown();
            org.junit.Assert.assertTrue("scheduled workers did not finish",workers.awaitTermination(3,TimeUnit.SECONDS));
        } finally { scheduler.close(); }
    }

}
