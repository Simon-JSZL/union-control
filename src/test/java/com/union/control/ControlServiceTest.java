package com.union.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.controller.AgentController;
import com.union.control.controller.ApiExceptionHandler;
import com.union.control.mapper.ControlMapper;
import com.union.control.service.ControlService;
import com.union.control.service.LocalAuth;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

public class ControlServiceTest {
    private ControlService service;
    private ControlMapper mapper;
    private MockMvc mvc;

    @Before
    public void setUp() {
        mapper = mock(ControlMapper.class);
        service = new ControlService(mapper, new ObjectMapper());
        mvc = standaloneSetup(new AgentController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    public void internalAgentRoutesRejectMissingCookie() throws Exception {
        mvc.perform(post("/agent/getConversationMessages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"thread-1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void aguiRunRejectsFrontendToolsBeforePersistence() {
        String body = "{\"threadId\":\"thread-1\",\"runId\":\"run-1\"," +
                "\"messages\":[{\"id\":\"m1\",\"role\":\"user\",\"content\":\"hello\"}]," +
                "\"state\":{},\"context\":[],\"forwardedProps\":{}," +
                "\"tools\":[{\"name\":\"unsafe\"}]}";
        assertThatThrownBy(() -> service.claimAguiRun(
                LocalAuth.cookieHeader(), body.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frontend tools");
    }

    @Test
    public void memoryPathCannotEscapeAuthenticatedUserNamespace() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("path", "another-user/personal/profile.md");
        payload.put("maxChars", 100);
        assertThatThrownBy(() -> service.memoryRead(LocalAuth.cookieHeader(), payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memory path");
    }

    @Test
    public void conversationMessagesReturnsScheduledRootMessagesInSequence() {
        String conversationId = "scheduled-6-test";
        when(mapper.requireOwned(conversationId, LocalAuth.USER_ID, false)).thenReturn(1L);
        Map<String, Object> user = message(
                "scheduled-user-6", "user", "{\"content\":\"执行定时任务\"}");
        Map<String, Object> assistant = message(
                "scheduled-assistant-6", "assistant", "{\"content\":\"任务结果\"}");
        when(mapper.findRootMessages(LocalAuth.USER_ID, conversationId))
                .thenReturn(Arrays.asList(user, assistant));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("conversationId", conversationId);

        Map<String, Object> response = service.conversationMessages(
                LocalAuth.cookieHeader(), request);

        @SuppressWarnings("unchecked") List<Map<String, Object>> messages =
                (List<Map<String, Object>>) (List<?>) response.get("messages");
        assertThat(messages).extracting("id")
                .containsExactly("scheduled-user-6", "scheduled-assistant-6");
        assertThat(messages).extracting("role").containsExactly("user", "assistant");
        assertThat(messages).extracting("content")
                .containsExactly("执行定时任务", "任务结果");
    }

    private static Map<String, Object> message(String id, String role, String payload) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("messageId", id);
        value.put("role", role);
        value.put("payload", payload);
        return value;
    }
}
