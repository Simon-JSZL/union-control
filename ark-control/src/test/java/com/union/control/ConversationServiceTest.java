package com.union.control;

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

public class ConversationServiceTest {
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
        assertFalse(mapper.contains("DELETE FROM agent_scheduled_task_run"));
    }
}
