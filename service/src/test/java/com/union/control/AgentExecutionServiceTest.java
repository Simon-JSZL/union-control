package com.union.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.local.security.LocalCasRealm;
import com.union.control.mapper.AgentExecutionMapper;
import com.union.control.mapper.ConversationMapper;
import com.union.control.service.AgentExecutionService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AgentExecutionServiceTest {
    private AgentExecutionService service;
    private AgentExecutionMapper executions;
    private ConversationMapper conversations;

    @Before
    public void setUp() {
        ShiroTestSupport.bindLocalUser();
        executions = mock(AgentExecutionMapper.class);
        conversations = mock(ConversationMapper.class);
        service = new AgentExecutionService(executions, conversations, new ObjectMapper());
    }

    @After
    public void tearDown() { ShiroTestSupport.clear(); }

    @Test
    public void aguiRunRejectsFrontendToolsBeforePersistence() {
        String body = "{\"threadId\":\"thread-1\",\"runId\":\"run-1\"," +
                "\"messages\":[{\"id\":\"m1\",\"role\":\"user\",\"content\":\"hello\"}]," +
                "\"state\":{},\"context\":[],\"forwardedProps\":{}," +
                "\"tools\":[{\"name\":\"unsafe\"}]}";

        assertThatThrownBy(() -> service.claimAguiRun(body.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frontend tools");
    }

    @Test
    public void claimChecksOnlyTheCurrentRootInsteadOfLoadingTheExecutionTree() {
        String body = "{\"threadId\":\"thread-1\",\"runId\":\"run-1\"," +
                "\"messages\":[{\"id\":\"m1\",\"role\":\"user\",\"content\":\"hello\"}]," +
                "\"state\":{},\"context\":[],\"forwardedProps\":{},\"tools\":[]}";
        when(executions.findCurrentRoots(LocalCasRealm.USER_ID, false))
                .thenReturn(Collections.<Map<String, Object>>emptyList());
        when(conversations.requireOwnedActive("thread-1", LocalCasRealm.USER_ID)).thenReturn(1L);
        when(executions.findExecution(LocalCasRealm.USER_ID, "thread-1", "run-1", false))
                .thenReturn(Collections.singletonList(root("running", null)));

        service.claimAguiRun(body.getBytes(StandardCharsets.UTF_8));

        verify(executions).findCurrentRoots(LocalCasRealm.USER_ID, false);
        verify(executions, never()).findExecutions(
                org.mockito.Matchers.anyString(), org.mockito.Matchers.anyString());
    }

    @Test
    public void cancellationWinsOverACompletedRunCallback() {
        when(executions.findExecution(LocalCasRealm.USER_ID, "thread-1", "run-1", true))
                .thenReturn(Collections.singletonList(root("cancel_requested", "user_cancelled")));
        when(executions.finishExecution(7L, "cancelled", "user_cancelled")).thenReturn(1);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", "thread-1");
        payload.put("runId", "run-1");
        payload.put("status", "completed");

        service.completeRun(payload);

        verify(executions).updateChildrenStatus(7L, "cancelled", "user_cancelled");
        verify(executions).finishExecution(7L, "cancelled", "user_cancelled");
    }

    private static Map<String, Object> root(String status, String errorCode) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", 7L);
        root.put("runId", "run-1");
        root.put("conversationId", "thread-1");
        root.put("parentExecutionId", null);
        root.put("agentName", "UnionCoordinatorAgent");
        root.put("status", status);
        root.put("errorCode", errorCode);
        return root;
    }
}
