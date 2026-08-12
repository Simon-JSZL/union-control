package com.union.control.scheduled;

import com.union.control.controller.ApiExceptionHandler;
import com.union.control.service.LocalAuth;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyMap;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

public class ScheduledTaskControllerContractTest {
    @Test
    public void exposesOnlyBrowserMysqlScheduledRoutes() throws Exception {
        ScheduledTaskService service = mock(ScheduledTaskService.class);
        when(service.create(anyString(), anyMap())).thenReturn(Collections.emptyMap());
        when(service.list(anyString(), anyString(), anyString(),
                org.mockito.Matchers.anyInt(), org.mockito.Matchers.anyInt()))
                .thenReturn(Collections.emptyMap());
        MockMvc mvc = standaloneSetup(new ScheduledTaskController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();

        mvc.perform(post("/llm/scheduledTaskCreate")
                .header("Cookie", LocalAuth.cookieHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk());
        mvc.perform(get("/llm/scheduledTaskList")
                .header("Cookie", LocalAuth.cookieHeader()))
                .andExpect(status().isOk());
        verify(service).create(LocalAuth.cookieHeader(), Collections.emptyMap());
    }

    @Test
    public void removedModelCallbackContextAndToolRoutesAreAbsent() throws Exception {
        MockMvc mvc = standaloneSetup(new ScheduledTaskController(
                mock(ScheduledTaskService.class))).build();
        for (String route : new String[]{
                "/llm/scheduledTaskDraft",
                "/agent/scheduledTaskRunContext",
                "/agent/scheduledTaskRunComplete",
                "/agent/scheduledToolGetOrgInfo",
                "/agent/scheduledToolQueryBigData",
                "/agent/scheduledToolAnnounceList",
                "/agent/scheduledToolGetJiraInfo"}) {
            mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    public void openKeepsTheWebConversationIdContract() throws Exception {
        ScheduledTaskService service = mock(ScheduledTaskService.class);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationId", "scheduled-shared-1");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        when(service.open(LocalAuth.cookieHeader(), 7L)).thenReturn(response);
        MockMvc mvc = standaloneSetup(new ScheduledTaskController(service)).build();

        mvc.perform(post("/llm/scheduledTaskRunOpen")
                .header("Cookie", LocalAuth.cookieHeader())
                .contentType(MediaType.APPLICATION_JSON).content("{\"runId\":7}"))
                .andExpect(status().isOk())
                .andExpect(content().json(
                        "{\"success\":true,\"data\":{\"conversationId\":\"scheduled-shared-1\"}}"));
    }
}
