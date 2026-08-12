package com.union.control.scheduled;

import com.union.control.controller.ApiExceptionHandler;
import com.union.control.service.ControlService;
import com.union.control.service.AgentProxyService;
import com.union.control.service.LocalAuth;
import com.union.control.service.RunningAnalysisMockService;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

public class ScheduledTaskControllerContractTest {
    @Test
    public void rejectsOversizedDraftBeforeCallingPyApp() {
        ScheduledTaskController controller = new ScheduledTaskController(
                mock(ScheduledTaskService.class), mock(AgentProxyService.class),
                new RunningAnalysisMockService(value -> {}));
        assertThatThrownBy(() -> controller.draft(
                "CASSESSIONID=session-1", new byte[65537]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("过大");
    }

    @Test
    public void browserRoutesUseTheCallerCookie() {
        ScheduledTaskService service = mock(ScheduledTaskService.class);
        AgentProxyService proxy = mock(AgentProxyService.class);
        ScheduledTaskController controller = new ScheduledTaskController(
                service, proxy, new RunningAnalysisMockService(value -> {}));
        String cookie = "CASSESSIONID=session-1";

        controller.list(cookie, null, null, 1, 20);
        verify(service).list(cookie, null, null, 1, 20);
    }

    @Test
    public void draftForwardsOnlyTheValidatedCasSessionCookie() {
        ScheduledTaskService service = mock(ScheduledTaskService.class);
        AgentProxyService proxy = mock(AgentProxyService.class);
        when(proxy.draftScheduledTask(anyString(), any(byte[].class))).thenReturn(Collections.emptyMap());
        ScheduledTaskController controller = new ScheduledTaskController(
                service, proxy, new RunningAnalysisMockService(value -> {}));
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);

        controller.draft("theme=dark; CASSESSIONID=session-1; access_token=secret", payload);

        verify(proxy).draftScheduledTask(eq(LocalAuth.cookieHeader()), same(payload));
    }

    @Test
    public void scheduledToolsFailClosedWhenTheRunOwnerIsNotTheLocalMockUser() {
        ScheduledTaskService service = mock(ScheduledTaskService.class);
        AgentProxyService proxy = mock(AgentProxyService.class);
        RunningAnalysisMockService tools = mock(RunningAnalysisMockService.class);
        ScheduledTaskController controller = new ScheduledTaskController(service, proxy, tools);
        when(service.requireRunnable("Bearer token", 7L)).thenReturn("user-2");
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("runId", 7L);

        assertThatThrownBy(() -> controller.getOrgInfo("Bearer token", payload))
                .isInstanceOf(ControlService.UnauthorizedException.class);
        assertThatThrownBy(() -> controller.queryBigData("Bearer token", payload))
                .isInstanceOf(ControlService.UnauthorizedException.class);
        assertThatThrownBy(() -> controller.announceList("Bearer token", payload))
                .isInstanceOf(ControlService.UnauthorizedException.class);
        assertThatThrownBy(() -> controller.getJiraInfo("Bearer token", payload))
                .isInstanceOf(ControlService.UnauthorizedException.class);
        verifyZeroInteractions(tools);
    }

    @Test
    public void scheduledToolsBindTheTrustedRunOwnerToTheLocalSession() {
        ScheduledTaskService service = mock(ScheduledTaskService.class);
        AgentProxyService proxy = mock(AgentProxyService.class);
        RunningAnalysisMockService tools = mock(RunningAnalysisMockService.class);
        ScheduledTaskController controller = new ScheduledTaskController(service, proxy, tools);
        when(service.requireRunnable("Bearer token", 7L)).thenReturn(LocalAuth.USER_ID);
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("runId", 7L);

        controller.getOrgInfo("Bearer token", payload);
        controller.queryBigData("Bearer token", payload);
        controller.announceList("Bearer token", payload);
        controller.getJiraInfo("Bearer token", payload);

        verify(tools).getOrgInfo(LocalAuth.cookieHeader(), payload);
        verify(tools).queryBigData(LocalAuth.cookieHeader(), payload);
        verify(tools).announceList(LocalAuth.cookieHeader(), payload);
        verify(tools).getJiraInfo(LocalAuth.cookieHeader(), payload);
    }

    @Test
    public void browserPostRoutesRejectNonJsonBodies() throws Exception {
        MockMvc mvc = standaloneSetup(new ScheduledTaskController(
                mock(ScheduledTaskService.class), mock(AgentProxyService.class),
                new RunningAnalysisMockService(value -> {})))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        String[] routes = {
                "/llm/scheduledTaskDraft",
                "/llm/scheduledTaskCreate",
                "/llm/scheduledTaskUpdate",
                "/llm/scheduledTaskStart",
                "/llm/scheduledTaskPause",
                "/llm/scheduledTaskDiscard",
                "/llm/scheduledTaskRunOpen"
        };

        for (String route : routes) {
            mvc.perform(post(route).header("Cookie", LocalAuth.cookieHeader())
                    .contentType(MediaType.TEXT_PLAIN).content("{}"))
                    .andExpect(status().isUnsupportedMediaType());
        }
    }

    @Test
    public void updateForwardsTheCallerCookieAndPayload() {
        ScheduledTaskService service = mock(ScheduledTaskService.class);
        ScheduledTaskController controller = new ScheduledTaskController(
                service, mock(AgentProxyService.class),
                new RunningAnalysisMockService(value -> {}));
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("taskId", 7L);

        controller.update("CASSESSIONID=session-1", payload);

        verify(service).update("CASSESSIONID=session-1", payload);
    }

    @Test
    public void exposesOnlyFixedGetAndPostRoutes() throws Exception {
        ScheduledTaskService service = mock(ScheduledTaskService.class);
        AgentProxyService proxy = mock(AgentProxyService.class);
        when(service.list(anyString(), anyString(), anyString(), anyInt(), anyInt())).thenReturn(ok());
        when(service.detail(anyString(), anyLong())).thenReturn(ok());
        when(service.runs(anyString(), anyLong(), anyInt(), anyInt())).thenReturn(ok());
        when(service.runDetail(anyString(), anyLong())).thenReturn(ok());
        when(service.unread(anyString())).thenReturn(ok());
        when(service.context(anyString(), anyLong())).thenReturn(ok());
        MockMvc mvc = standaloneSetup(new ScheduledTaskController(
                service, proxy, new RunningAnalysisMockService(value -> {})))
                .setControllerAdvice(new ApiExceptionHandler()).build();

        mvc.perform(get("/llm/scheduledTaskList").header("Cookie", "CASSESSIONID=session-1"))
                .andExpect(status().isOk());
        mvc.perform(get("/llm/scheduledTaskDetail").header("Cookie", "CASSESSIONID=session-1")
                .param("taskId", "1"))
                .andExpect(status().isOk());
        mvc.perform(get("/llm/scheduledTaskRunList").header("Cookie", "CASSESSIONID=session-1")
                .param("taskId", "1"))
                .andExpect(status().isOk());
        mvc.perform(get("/llm/scheduledTaskRunDetail").header("Cookie", "CASSESSIONID=session-1")
                .param("runId", "1"))
                .andExpect(status().isOk());
        mvc.perform(get("/llm/scheduledTaskUnread").header("Cookie", "CASSESSIONID=session-1"))
                .andExpect(status().isOk());
        mvc.perform(post("/agent/scheduledTaskRunContext")
                .header("Authorization", "Bearer token")
                .contentType("application/json").content("{\"runId\":1}"))
                .andExpect(status().isOk());
    }

    private static java.util.Map<String, Object> ok() {
        java.util.Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("success", true);
        value.put("data", Collections.emptyMap());
        return value;
    }
}
