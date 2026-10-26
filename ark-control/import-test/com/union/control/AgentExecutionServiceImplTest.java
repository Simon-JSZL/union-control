package com.union.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.mapper.AgentExecutionMapper;
import com.union.control.mapper.ConversationMapper;
import com.union.control.service.impl.AgentExecutionServiceImpl;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class AgentExecutionServiceImplTest {
    private AgentExecutionServiceImpl service;
    private AgentExecutionMapper executions;
    private ConversationMapper conversations;

    @Before
    public void setUp() {
        executions = mock(AgentExecutionMapper.class);
        conversations = mock(ConversationMapper.class);
        service = new AgentExecutionServiceImpl(executions, conversations, new ObjectMapper());
        when(conversations.requireOwned("thread-1", TestJson.USER_ID, true)).thenReturn(1L);
    }

    @Test
    public void aguiRunLeavesFrontendFeatureValidationToPyApp() {
        String body = "{\"threadId\":\"thread-1\",\"runId\":\"run-1\"," +
                "\"messages\":[{\"id\":\"m1\",\"role\":\"user\",\"content\":\"hello\"}]," +
                "\"state\":{},\"context\":[],\"forwardedProps\":{}," +
                "\"tools\":[{\"name\":\"unsafe\"}]}";
        when(conversations.requireOwnedActive("thread-1", TestJson.USER_ID)).thenReturn(1L);
        when(executions.findExecution(TestJson.USER_ID, "thread-1", "run-1", false))
                .thenReturn(Collections.singletonList(root("running", null)));

        service.claimAguiRun(TestJson.request(body));

        verify(executions).insertRootExecution("run-1", "thread-1", TestJson.USER_ID);
    }

    @Test
    public void aguiRunRejectsAnExistingConversationOwnedByAnotherUser() {
        String body = "{\"threadId\":\"foreign-thread\",\"runId\":\"run-1\"," +
                "\"messages\":[],\"state\":{},\"context\":[],\"forwardedProps\":{}}";
        when(conversations.requireOwnedActive("foreign-thread", TestJson.USER_ID))
                .thenReturn(null);

        assertThatThrownBy(() -> service.claimAguiRun(TestJson.request(body)))
                .isInstanceOf(java.util.NoSuchElementException.class);
        verify(executions, never()).insertRootExecution(
                "run-1", "foreign-thread", TestJson.USER_ID);
    }

    @Test
    public void completionPersistsTheStatusChosenByPyApp() {
        when(executions.findExecution(TestJson.USER_ID, "thread-1", "run-1", true))
                .thenReturn(Collections.singletonList(root("running", null)));
        when(executions.finishExecution(7L, "sdk_terminal", null)).thenReturn(1);
        Map<String, Object> rootExecution =
                execution("run-1", null, "UnionCoordinatorAgent", null, null);
        rootExecution.put("status", "sdk_terminal");
        Map<String, Object> payload = completion(rootExecution);

        service.completeRun(TestJson.request(payload));

        verify(executions).finishExecution(7L, "sdk_terminal", null);
    }

    @Test
    public void completionLocksTheConversationBeforeAllocatingMessageSequences() {
        when(executions.findExecution(TestJson.USER_ID, "thread-1", "run-1", true))
                .thenReturn(Collections.singletonList(root("running", null)));
        when(executions.finishExecution(7L, "completed", null)).thenReturn(1);

        service.completeRun(TestJson.request(completion(
                execution("run-1", null, "KnowledgeAgent", null, null))));

        InOrder order = inOrder(conversations, executions);
        order.verify(conversations).requireOwned("thread-1", TestJson.USER_ID, true);
        order.verify(executions).findExecution(
                TestJson.USER_ID, "thread-1", "run-1", true);
        order.verify(conversations).currentMessageSequence("thread-1");
    }

    @Test
    public void completionSelectsTheRootAgentWithoutAnExtraCallback() {
        when(executions.findExecution(TestJson.USER_ID, "thread-1", "run-1", true))
                .thenReturn(Collections.singletonList(root("running", null)));
        when(executions.finishExecution(7L, "completed", null)).thenReturn(1);
        Map<String, Object> payload = completion(
                execution("run-1", null, "KnowledgeAgent", null, null));

        service.completeRun(TestJson.request(payload));

        verify(executions).updateRootAgent(7L, "KnowledgeAgent");
        verify(executions).finishExecution(7L, "completed", null);
    }

    @Test
    public void completionDoesNotLimitMessageCount() {
        when(conversations.currentMessageSequence("thread-1")).thenReturn(3L);
        when(executions.findExecution(TestJson.USER_ID, "thread-1", "run-1", true))
                .thenReturn(Collections.singletonList(root("running", null)));
        when(executions.finishExecution(7L, "completed", null)).thenReturn(1);
        Map<String, Object> payload = completion(
                execution("run-1", null, "UnionCoordinatorAgent", null, null));
        List<Map<String, Object>> messages = new ArrayList<>();
        for (int index = 0; index < 1001; index++) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("id", "message-" + index);
            message.put("role", "assistant");
            message.put("content", "value");
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("runId", "run-1");
            envelope.put("message", message);
            messages.add(envelope);
        }
        payload.put("messages", messages);

        service.completeRun(TestJson.request(payload));

        verify(conversations).touchConversation("thread-1", TestJson.USER_ID);
    }

    private static Map<String, Object> completion(Map<String, Object>... executions) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", "thread-1");
        payload.put("runId", "run-1");
        payload.put("executions", Arrays.asList(executions));
        payload.put("messages", Collections.emptyList());
        return payload;
    }

    private static Map<String, Object> execution(
            String runId, String parentRunId, String agentName,
            String callId, String task) {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("runId", runId);
        execution.put("parentRunId", parentRunId);
        execution.put("agentName", agentName);
        execution.put("delegationToolCallId", callId);
        execution.put("task", task);
        execution.put("status", "completed");
        execution.put("errorCode", null);
        return execution;
    }

    private static Map<String, Object> root(String status, String errorCode) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", 7L);
        root.put("runId", "run-1");
        root.put("conversationId", "thread-1");
        root.put("parentExecutionId", null);
        root.put("agentName", null);
        root.put("status", status);
        root.put("errorCode", errorCode);
        return root;
    }

    @Test public void persistsChildExecutionsAndRejectsBrokenTopologyAndEnvelopes() {
        when(executions.findExecution(TestJson.USER_ID,"thread-1","run-1",true)).thenReturn(Collections.singletonList(root("running",null)));
        Map<String,Object> childRow=root("running",null); childRow.put("id",8L); childRow.put("parentExecutionId",7L);
        when(executions.findExecution(TestJson.USER_ID,"thread-1","child",true)).thenReturn(Collections.singletonList(childRow));
        when(executions.finishExecution(7L,"completed",null)).thenReturn(1); when(executions.finishExecution(8L,"completed",null)).thenReturn(1);
        Map<String,Object> payload=completion(execution("run-1",null,"Root",null,null),execution("child","run-1","Specialist","call","task"));
        service.completeRun(TestJson.request(payload));
        verify(executions).insertChildExecution("child","thread-1",TestJson.USER_ID,7L,"Specialist","call","task");
        payload=completion(execution("run-1",null,"Root",null,null),execution("child","missing","Specialist",null,null));
        final Map<String,Object> invalidParent=payload;
        assertThatThrownBy(() -> service.completeRun(TestJson.request(invalidParent))).isInstanceOf(IllegalArgumentException.class);
        Map<String,Object> normal=completion(execution("run-1",null,"Root",null,null));
        for (Object invalid:new Object[]{"bad",Collections.emptyList(),Arrays.asList("bad"),Arrays.asList(execution("other",null,"Root",null,null)),Arrays.asList(execution("run-1",null,"Root",null,null),execution("run-1",null,"Root",null,null))}) {
            normal.put("executions",invalid);
            assertThatThrownBy(() -> service.completeRun(TestJson.request(normal))).isInstanceOf(IllegalArgumentException.class);
        }
        normal.put("executions",Collections.singletonList(execution("run-1",null,"Root",null,null)));
        for (Object invalid:new Object[]{"bad",Arrays.asList("bad"),Arrays.asList(Collections.singletonMap("runId","run-1"))}) {
            normal.put("messages",invalid);
            assertThatThrownBy(() -> service.completeRun(TestJson.request(normal))).isInstanceOf(IllegalArgumentException.class);
        }
        Map<String,Object> message=new LinkedHashMap<>(); message.put("id","m"); message.put("role","assistant");
        Map<String,Object> envelope=new LinkedHashMap<>(); envelope.put("runId","missing"); envelope.put("message",message);
        normal.put("messages",Collections.singletonList(envelope));
        assertThatThrownBy(() -> service.completeRun(TestJson.request(normal))).isInstanceOf(IllegalArgumentException.class);
        normal.put("messages",Collections.emptyList());
        when(executions.finishExecution(7L,"completed",null)).thenReturn(0);
        assertThatThrownBy(() -> service.completeRun(TestJson.request(normal))).isInstanceOf(IllegalStateException.class);
        when(executions.findExecution(TestJson.USER_ID,"thread-1","run-1",true)).thenReturn(Collections.emptyList());
        assertThatThrownBy(() -> service.completeRun(TestJson.request(normal))).isInstanceOf(IllegalStateException.class);
        when(executions.findExecution(TestJson.USER_ID,"thread-1","run-1",true)).thenReturn(Collections.singletonList(childRow));
        assertThatThrownBy(() -> service.completeRun(TestJson.request(normal))).isInstanceOf(IllegalArgumentException.class);
        when(conversations.requireOwned("thread-1",TestJson.USER_ID,true)).thenReturn(null);
        assertThatThrownBy(() -> service.completeRun(TestJson.request(normal))).isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test public void existingConversationsAndFallbackTitlesUseTheSameClaimPath() {
        when(conversations.requireOwnedActive("thread-1",TestJson.USER_ID)).thenReturn(1L);
        when(executions.findExecution(TestJson.USER_ID,"thread-1","run-1",false)).thenReturn(Collections.singletonList(root("running",null)));
        org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException("existing")).when(conversations).insertConversation(org.mockito.Matchers.anyString(),org.mockito.Matchers.anyString(),org.mockito.Matchers.anyString());
        for (Object messages:new Object[]{null,Arrays.asList("invalid"),Arrays.asList(Collections.singletonMap("content",1)),Arrays.asList(Collections.singletonMap("content"," "))}) {
            service.claimAguiRun(TestJson.request("threadId","thread-1","runId","run-1","messages",messages));
        }
        verify(conversations,org.mockito.Mockito.times(4)).insertConversation("thread-1",TestJson.USER_ID,"新会话");
    }


    @Test public void serializationFailureDoesNotInsertAnInvalidMessage() throws Exception {
        ObjectMapper broken=org.mockito.Mockito.spy(new ObjectMapper());
        org.mockito.Mockito.doThrow(new com.fasterxml.jackson.core.JsonProcessingException("serialization failed") {})
                .when(broken).writeValueAsString(org.mockito.Matchers.any());
        AgentExecutionServiceImpl target=new AgentExecutionServiceImpl(executions,conversations,broken);
        when(executions.findExecution(TestJson.USER_ID,"thread-1","run-1",true)).thenReturn(Collections.singletonList(root("running",null)));
        Map<String,Object> message=new ObjectMapper().readValue("{\"runId\":\"run-1\",\"message\":{\"id\":\"m1\",\"role\":\"assistant\",\"content\":\"text\"}}",Map.class);
        Map<String,Object> payload=completion(execution("run-1",null,"Root",null,null)); payload.put("messages",Collections.singletonList(message));
        assertThatThrownBy(() -> target.completeRun(TestJson.request(payload))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("有效 JSON");
        verify(conversations,never()).insertMessage(org.mockito.Matchers.anyString(),org.mockito.Matchers.anyString(),org.mockito.Matchers.anyString(),org.mockito.Matchers.anyLong(),org.mockito.Matchers.anyString(),org.mockito.Matchers.anyLong(),org.mockito.Matchers.anyString());
    }

}
