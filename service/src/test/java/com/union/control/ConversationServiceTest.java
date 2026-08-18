package com.union.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.mapper.AgentExecutionMapper;
import com.union.control.mapper.ConversationMapper;
import com.union.control.service.ConversationService;
import org.junit.Test;

import java.util.Collections;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ConversationServiceTest {
    @Test
    public void parsesAuthenticatedUserFromJsonBeforeCallingMapper() {
        ConversationMapper conversations = mock(ConversationMapper.class);
        AgentExecutionMapper executions = mock(AgentExecutionMapper.class);
        ConversationService service = new ConversationService(
                conversations, executions, new ObjectMapper());
        when(conversations.findConversations(TestJson.USER_ID, 20))
                .thenReturn(Collections.emptyList());

        service.conversations(TestJson.request("limit", 20));

        verify(conversations).findConversations(TestJson.USER_ID, 20);
    }
}
