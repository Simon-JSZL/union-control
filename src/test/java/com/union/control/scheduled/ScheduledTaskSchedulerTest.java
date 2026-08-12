package com.union.control.scheduled;

import com.union.control.service.AgentProxyService;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ScheduledTaskSchedulerTest {
    @Test
    public void enablingWithoutTheSharedTokenFailsClosed() {
        assertThatThrownBy(() -> new ScheduledTaskScheduler(
                mock(ScheduledTaskService.class), mock(AgentProxyService.class),
                true, 1, 1, 1, 60))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SCHEDULED_TASK_TOKEN");
    }

    @Test
    public void doesNotSubmitTheSamePendingRunAgainWhileItsWorkerIsBusy() throws Exception {
        ScheduledTaskService service = mock(ScheduledTaskService.class);
        AgentProxyService proxy = mock(AgentProxyService.class);
        when(proxy.isScheduledTaskConfigured()).thenReturn(true);
        when(service.pendingRunIds(1)).thenReturn(Collections.singletonList(7L));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(service.beginRun(7L)).thenAnswer(invocation -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return false;
        });
        ScheduledTaskScheduler scheduler = new ScheduledTaskScheduler(
                service, proxy, true, 1, 2, 1, 60);
        try {
            scheduler.scan();
            if (!entered.await(2, TimeUnit.SECONDS))
                throw new AssertionError("worker did not start");

            scheduler.scan();

            verify(service, timeout(200).times(1)).beginRun(7L);
        } finally {
            release.countDown();
            scheduler.close();
        }
    }

    @Test
    public void completedWorkerAllowsThePendingRunToBeConsideredAgain() throws Exception {
        ScheduledTaskService service = mock(ScheduledTaskService.class);
        AgentProxyService proxy = mock(AgentProxyService.class);
        when(proxy.isScheduledTaskConfigured()).thenReturn(true);
        when(service.pendingRunIds(1)).thenReturn(Collections.singletonList(7L));
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch secondCall = new CountDownLatch(1);
        when(service.beginRun(7L)).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) secondCall.countDown();
            return false;
        });
        ScheduledTaskScheduler scheduler = new ScheduledTaskScheduler(
                service, proxy, true, 1, 2, 1, 60);
        try {
            scheduler.scan();
            verify(service, timeout(1000).times(1)).beginRun(7L);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (secondCall.getCount() > 0 && System.nanoTime() < deadline) {
                scheduler.scan();
                Thread.yield();
            }

            assertThat(secondCall.getCount()).isZero();
        } finally {
            scheduler.close();
        }
    }

    @Test
    public void rejectedSubmissionCanBeRetriedAfterQueueCapacityReturns() throws Exception {
        ScheduledTaskService service = mock(ScheduledTaskService.class);
        AgentProxyService proxy = mock(AgentProxyService.class);
        when(proxy.isScheduledTaskConfigured()).thenReturn(true);
        when(service.pendingRunIds(1))
                .thenReturn(Collections.singletonList(1L))
                .thenReturn(Collections.singletonList(2L))
                .thenReturn(Collections.singletonList(3L))
                .thenReturn(Collections.singletonList(3L));
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch thirdEntered = new CountDownLatch(1);
        when(service.beginRun(anyLong())).thenAnswer(invocation -> {
            long runId = (Long) invocation.getArguments()[0];
            if (runId == 1L) {
                firstEntered.countDown();
                releaseFirst.await(2, TimeUnit.SECONDS);
            } else if (runId == 2L) {
                secondEntered.countDown();
            } else if (runId == 3L) {
                thirdEntered.countDown();
            }
            return false;
        });
        ScheduledTaskScheduler scheduler = new ScheduledTaskScheduler(
                service, proxy, true, 1, 1, 1, 60);
        try {
            scheduler.scan();
            if (!firstEntered.await(2, TimeUnit.SECONDS))
                throw new AssertionError("first worker did not start");
            scheduler.scan();
            scheduler.scan();
            releaseFirst.countDown();
            if (!secondEntered.await(2, TimeUnit.SECONDS))
                throw new AssertionError("queued worker did not start");

            scheduler.scan();

            assertThat(thirdEntered.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseFirst.countDown();
            scheduler.close();
        }
    }
}
