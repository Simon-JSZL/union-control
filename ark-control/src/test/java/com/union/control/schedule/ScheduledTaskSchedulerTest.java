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
}
