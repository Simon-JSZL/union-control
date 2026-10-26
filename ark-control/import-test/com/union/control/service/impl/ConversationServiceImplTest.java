package com.union.control.service.impl;
import com.union.control.TestJson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.mapper.AgentExecutionMapper;
import com.union.control.mapper.ConversationMapper;
import com.union.control.mapper.ScheduledTaskMapper;
import com.union.control.service.impl.ConversationServiceImpl;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ConversationServiceImplTest {
    @Test
    public void parsesAuthenticatedUserFromJsonBeforeCallingMapper() {
        ConversationMapper conversations = mock(ConversationMapper.class);
        AgentExecutionMapper executions = mock(AgentExecutionMapper.class);
        ScheduledTaskMapper scheduledTasks = mock(ScheduledTaskMapper.class);
        ConversationServiceImpl service = new ConversationServiceImpl(
                conversations, executions, scheduledTasks, new ObjectMapper());
        when(conversations.findConversations(TestJson.USER_ID, 20))
                .thenReturn(Collections.emptyList());

        service.conversations(TestJson.request("limit", 20));

        verify(conversations).findConversations(TestJson.USER_ID, 20);
    }

    @Test
    public void conversationDetailsLoadsExecutionsOnce() {
        ConversationMapper conversations = mock(ConversationMapper.class);
        AgentExecutionMapper executions = mock(AgentExecutionMapper.class);
        when(conversations.findConversation(TestJson.USER_ID, "thread-1"))
                .thenReturn(new java.util.LinkedHashMap<>());
        when(executions.findExecutions(TestJson.USER_ID, "thread-1"))
                .thenReturn(Collections.emptyList());
        new ConversationServiceImpl(conversations, executions,
                mock(ScheduledTaskMapper.class), new ObjectMapper())
                .conversation(TestJson.request("conversationId", "thread-1"));
        verify(executions).findExecutions(TestJson.USER_ID, "thread-1");
    }

    @Test
    public void deletingConversationAlsoDeletesItsClaimedScheduledRun() {
        ConversationMapper conversations = mock(ConversationMapper.class);
        AgentExecutionMapper executions = mock(AgentExecutionMapper.class);
        ScheduledTaskMapper scheduledTasks = mock(ScheduledTaskMapper.class);
        ConversationServiceImpl service = new ConversationServiceImpl(
                conversations, executions, scheduledTasks, new ObjectMapper());
        when(conversations.softDeleteConversation("scheduled-7", TestJson.USER_ID))
                .thenReturn(1);

        service.deleteConversation(TestJson.request("conversationId", "scheduled-7"));

        verify(scheduledTasks).softDeleteByConversation("scheduled-7", TestJson.USER_ID);
    }

    @Test
    public void conversationMessagesRejectMissingOwnership() {
        ConversationMapper conversations = mock(ConversationMapper.class);
        ConversationServiceImpl service = new ConversationServiceImpl(
                conversations, mock(AgentExecutionMapper.class),
                mock(ScheduledTaskMapper.class), new ObjectMapper());
        when(conversations.requireOwned("foreign-thread", TestJson.USER_ID, false))
                .thenReturn(null);

        assertThatThrownBy(() -> service.conversationMessages(
                TestJson.request("conversationId", "foreign-thread")))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    public void claimedScheduledRunCleanupIsALogicalDelete() throws Exception {
        String mapper = new String(Files.readAllBytes(Paths.get(
                "src/main/resources/mapper/ScheduledTaskMapper.xml")), StandardCharsets.UTF_8);

        assertTrue(mapper.contains("SET r.delete_flag=0"));
        assertFalse(mapper.contains("DELETE FROM t_m_announce_scheduled_run"));
    }

    @Test public void reconstructsRootAndChildMessagesAndPublicExecutions() throws Exception {
        ObjectMapper json=new ObjectMapper();
        ConversationMapper conversations=mock(ConversationMapper.class); AgentExecutionMapper executions=mock(AgentExecutionMapper.class);
        ConversationServiceImpl service=new ConversationServiceImpl(conversations,executions,mock(ScheduledTaskMapper.class),json);
        java.util.Map<String,Object> root=json.readValue("{\"id\":7,\"runId\":\"root\",\"parentExecutionId\":null}",java.util.Map.class);
        java.util.Map<String,Object> child=json.readValue("{\"id\":8,\"runId\":\"child\",\"parentRunId\":\"root\",\"parentExecutionId\":7,\"agentName\":\"Specialist\",\"status\":\"completed\"}",java.util.Map.class);
        java.util.Map<String,Object> emptyChild=new java.util.LinkedHashMap<>(child); emptyChild.put("runId","empty-child");
        java.util.Map<String,Object> message=json.readValue("{\"messageId\":\"m1\",\"role\":\"assistant\",\"payload\":\"{\\\"content\\\":\\\"hello\\\"}\",\"parentExecutionId\":null}",java.util.Map.class);
        java.util.Map<String,Object> childMessage=new java.util.LinkedHashMap<>(message); childMessage.put("parentExecutionId",7L); childMessage.put("runId","child");
        when(conversations.findConversation(TestJson.USER_ID,"thread")).thenReturn(new java.util.LinkedHashMap<>());
        when(executions.findExecutions(TestJson.USER_ID,"thread")).thenReturn(java.util.Arrays.asList(root,child,emptyChild));
        when(conversations.findBrowserMessages(TestJson.USER_ID,"thread")).thenReturn(java.util.Arrays.asList(message,childMessage,childMessage));
        java.util.Map<String,Object> response=service.conversation(TestJson.request("conversationId","thread"));
        com.fasterxml.jackson.databind.JsonNode data=json.valueToTree(response).get("data");
        org.assertj.core.api.Assertions.assertThat(data.get("messages").size()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(data.at("/messages/0/content").asText()).isEqualTo("hello");
        org.assertj.core.api.Assertions.assertThat(data.at("/messages/1/content/messages").size()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(data.at("/messages/2/content/messages").size()).isZero();
        org.assertj.core.api.Assertions.assertThat(data.at("/executions/0").has("id")).isFalse();
        when(conversations.requireOwned("thread",TestJson.USER_ID,false)).thenReturn(1L);
        when(conversations.findRootMessages(TestJson.USER_ID,"thread")).thenReturn(Collections.singletonList(message));
        org.assertj.core.api.Assertions.assertThat(json.valueToTree(service.conversationMessages(TestJson.request("conversationId","thread"))).at("/messages/0/content").asText()).isEqualTo("hello");
        for (String invalid:new String[]{"[]","bad"}) {
            message.put("payload",invalid);
            assertThatThrownBy(() -> service.conversationMessages(TestJson.request("conversationId","thread"))).isInstanceOf(IllegalStateException.class);
        }
        message.put("payload","{\"content\":\"hello\"}");
        childMessage.put("runId","missing");
        assertThatThrownBy(() -> service.conversation(TestJson.request("conversationId","thread"))).isInstanceOf(IllegalStateException.class).hasMessage("execution 数据损坏");
    }

    @Test public void metadataRenameDeleteAndMissingConversationKeepOwnerChecks() {
        ConversationMapper conversations=mock(ConversationMapper.class);
        ConversationServiceImpl service=new ConversationServiceImpl(conversations,mock(AgentExecutionMapper.class),mock(ScheduledTaskMapper.class),new ObjectMapper());
        org.assertj.core.api.Assertions.assertThat((java.util.Map<String,Object>)service.userInfo(TestJson.request()).get("data"))
                .containsEntry("userId",TestJson.USER_ID).containsEntry("orgCode",TestJson.ORG_CODE);
        String request=TestJson.request("conversationId","thread","title","title");
        assertThatThrownBy(() -> service.rename(request)).isInstanceOf(java.util.NoSuchElementException.class);
        when(conversations.updateConversationTitle("title","thread",TestJson.USER_ID)).thenReturn(1);
        when(conversations.findConversation(TestJson.USER_ID,"thread")).thenReturn(null);
        assertThatThrownBy(() -> service.rename(request)).isInstanceOf(java.util.NoSuchElementException.class);
        when(conversations.findConversation(TestJson.USER_ID,"thread")).thenReturn(Collections.singletonMap("title","title"));
        org.assertj.core.api.Assertions.assertThat(service.rename(request).get("data")).isEqualTo(Collections.singletonMap("title","title"));
        when(conversations.requireOwned("thread",TestJson.USER_ID,true)).thenReturn(1L);
        assertThatThrownBy(() -> service.deleteConversation(request)).isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test public void materializesExactlyTwoTrustedMessagesAndPropagatesWriteErrors() {
        ConversationMapper conversations=mock(ConversationMapper.class); AgentExecutionMapper executions=mock(AgentExecutionMapper.class);
        ConversationServiceImpl service=new ConversationServiceImpl(conversations,executions,mock(ScheduledTaskMapper.class),new ObjectMapper());
        org.mockito.Mockito.doAnswer(call -> { ((java.util.Map<String,Object>)call.getArguments()[0]).put("id",7L); return 1; })
                .when(executions).insertCompletedRootExecution(org.mockito.Matchers.anyMap());
        String id=service.materializeCompletedConversation("owner","title","prompt","result","Specialist");
        org.mockito.ArgumentCaptor<String> body=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(conversations).insertMessage(org.mockito.Matchers.eq(id),org.mockito.Matchers.eq("owner"),org.mockito.Matchers.anyString(),org.mockito.Matchers.eq(7L),org.mockito.Matchers.eq("user"),org.mockito.Matchers.eq(1L),body.capture());
        org.assertj.core.api.Assertions.assertThat(body.getValue()).isEqualTo("{\"content\":\"prompt\"}");
        verify(conversations).insertMessage(org.mockito.Matchers.eq(id),org.mockito.Matchers.eq("owner"),org.mockito.Matchers.anyString(),org.mockito.Matchers.eq(7L),org.mockito.Matchers.eq("assistant"),org.mockito.Matchers.eq(2L),org.mockito.Matchers.eq("{\"content\":\"result\"}"));
        org.mockito.Mockito.doThrow(new IllegalStateException("database unavailable")).when(conversations).insertMessage(
                org.mockito.Matchers.anyString(),org.mockito.Matchers.anyString(),org.mockito.Matchers.anyString(),org.mockito.Matchers.anyLong(),org.mockito.Matchers.anyString(),org.mockito.Matchers.anyLong(),org.mockito.Matchers.anyString());
        assertThatThrownBy(() -> service.materializeCompletedConversation("owner","title","prompt","result","Specialist")).isInstanceOf(IllegalArgumentException.class);
    }

}
