package com.union.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.local.security.LocalCasRealm;
import com.union.control.mapper.AgentExecutionMapper;
import com.union.control.mapper.ConversationMapper;
import com.union.control.service.ConversationService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ConversationServiceTest {
    private ConversationService service;
    private ConversationMapper conversations;
    private AgentExecutionMapper executions;

    @Before
    public void setUp() {
        ShiroTestSupport.bindLocalUser();
        conversations = mock(ConversationMapper.class);
        executions = mock(AgentExecutionMapper.class);
        service = new ConversationService(conversations, executions, new ObjectMapper());
    }

    @After
    public void tearDown() { ShiroTestSupport.clear(); }

    @Test
    public void materializesTrustedCompletedConversationThroughSharedMappers() {
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked") Map<String, Object> execution =
                    (Map<String, Object>) invocation.getArguments()[0];
            execution.put("id", 99L);
            return 1;
        }).when(executions).insertCompletedRootExecution(
                org.mockito.Matchers.<Map<String, Object>>any());

        String conversationId = service.materializeCompletedConversation(
                "日报", "生成日报", "日报内容", "Coordinator");

        assertThat(conversationId).startsWith("scheduled-");
        verify(conversations).insertConversation(conversationId, LocalCasRealm.USER_ID, "日报");
        verify(executions).insertCompletedRootExecution(
                org.mockito.Matchers.<Map<String, Object>>any());
        verify(conversations).insertMessage(org.mockito.Matchers.eq(conversationId),
                org.mockito.Matchers.eq(LocalCasRealm.USER_ID), org.mockito.Matchers.anyString(),
                org.mockito.Matchers.eq(99L), org.mockito.Matchers.eq("user"),
                org.mockito.Matchers.eq(1L), org.mockito.Matchers.contains("生成日报"));
        verify(conversations).insertMessage(org.mockito.Matchers.eq(conversationId),
                org.mockito.Matchers.eq(LocalCasRealm.USER_ID), org.mockito.Matchers.anyString(),
                org.mockito.Matchers.eq(99L), org.mockito.Matchers.eq("assistant"),
                org.mockito.Matchers.eq(2L), org.mockito.Matchers.contains("日报内容"));
    }

    @Test
    public void conversationMessagesReturnsScheduledRootMessagesInSequence() {
        String conversationId = "scheduled-6-test";
        when(conversations.requireOwned(conversationId, LocalCasRealm.USER_ID, false))
                .thenReturn(1L);
        when(conversations.findRootMessages(LocalCasRealm.USER_ID, conversationId))
                .thenReturn(Arrays.asList(
                        message("scheduled-user-6", "user", "{\"content\":\"执行定时任务\"}"),
                        message("scheduled-assistant-6", "assistant", "{\"content\":\"任务结果\"}")));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("conversationId", conversationId);

        Map<String, Object> response = service.conversationMessages(request);

        @SuppressWarnings("unchecked") List<Map<String, Object>> messages =
                (List<Map<String, Object>>) (List<?>) response.get("messages");
        assertThat(messages).extracting("id")
                .containsExactly("scheduled-user-6", "scheduled-assistant-6");
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
