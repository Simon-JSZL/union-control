package com.epcc.arkweb.web.llm;

import com.epcc.arkweb.ShiroTestSupport;
import com.epcc.arkweb.helper.AuthContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.After;
import org.mockito.ArgumentCaptor;
import com.union.control.service.AgentExecutionService;
import com.union.control.service.AgentResponse;
import com.union.control.service.AgentProxyService;
import com.union.control.service.ConversationService;
import org.junit.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

public class LlmControllerTest {
    @Before public void bindUser() { ShiroTestSupport.bindLocalUser(); }
    @After public void clearUser() { ShiroTestSupport.clear(); }

    @Test
    public void returnsSynchronousAgentResponseAsJson() {
        ConversationService conversations = mock(ConversationService.class);
        AgentExecutionService executions = mock(AgentExecutionService.class);
        AgentProxyService gateway = mock(AgentProxyService.class);
        ObjectMapper json = new ObjectMapper();
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        when(gateway.sync("CASSESSIONID=session", "{}"))
                .thenReturn(new AgentResponse(200, "{\"content\":{\"status\":\"READY\"}}"));

        ResponseEntity<String> response = new LlmController(
                conversations, executions, gateway, json, "http://py-app", new RestTemplate())
                .chatMessageSync("CASSESSIONID=session", payload);

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isEqualTo("{\"content\":{\"status\":\"READY\"}}");
    }

    @Test
    public void streamsPyAppHttpResponseDirectlyToTheBrowser() throws Exception {
        ConversationService conversations = mock(ConversationService.class);
        AgentExecutionService executions = mock(AgentExecutionService.class);
        AgentProxyService gateway = mock(AgentProxyService.class);
        ObjectMapper json = new ObjectMapper();
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("conversationId", "conversation-1");
        execution.put("runId", "run-1");
        Map<String, Object> claimed = new LinkedHashMap<>();
        claimed.put("data", execution);
        when(executions.claimAguiRun("{\"userId\":\"user-1\"}")).thenReturn(claimed);
        server.expect(requestTo("http://py-app/agent/v1/runs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.COOKIE, "CASSESSIONID=session"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().bytes(payload))
                .andRespond(withSuccess("data: first\n\ndata: second\n\n", MediaType.TEXT_EVENT_STREAM));
        MockHttpServletResponse response = new MockHttpServletResponse();

        new LlmController(conversations, executions, gateway, json, "http://py-app", http)
                .chatMessage("CASSESSIONID=session", payload, response);

        assertThat(response.getContentAsString()).isEqualTo("data: first\n\ndata: second\n\n");
        assertThat(response.getHeader(HttpHeaders.CONTENT_TYPE)).isEqualTo("text/event-stream");
        assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no");
        server.verify();
        verify(executions).claimAguiRun("{\"userId\":\"user-1\"}");
    }
    @Test
    public void conversationUsesOnlyItsRequiredOwnerFromTheBrowserSession() throws Exception {
        AuthContextHolder.getAuthUserDetails().setOrgCode(null);
        AuthContextHolder.getAuthUserDetails().setRoleId(null);
        ConversationService conversations = mock(ConversationService.class);
        LlmController controller = new LlmController(conversations, mock(AgentExecutionService.class),
                mock(AgentProxyService.class), new ObjectMapper(), "http://py-app", new RestTemplate());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", "thread-1");
        payload.put("title", "new title");
        payload.put("userId", "forged");
        controller.conversationTitle(payload);
        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(conversations).rename(input.capture());
        Map<?, ?> parsed = new ObjectMapper().readValue(input.getValue(), Map.class);
        payload.put("userId", "user-1");
        assertThat(parsed).isEqualTo(payload);
    }

    @Test(timeout = 5000)
    public void streamingStopsWaitingForASilentUpstream() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try { Thread.sleep(150); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            ObjectMapper json = new ObjectMapper();
            AgentExecutionService executions = mock(AgentExecutionService.class);
            byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
            Map<String, Object> execution = new LinkedHashMap<>();
            execution.put("conversationId", "thread-1");
            execution.put("runId", "run-1");
            when(executions.claimAguiRun("{\"userId\":\"user-1\"}"))
                    .thenReturn(java.util.Collections.singletonMap("data", execution));
            LlmController controller = new LlmController(mock(ConversationService.class), executions,
                    mock(AgentProxyService.class), json,
                    "http://127.0.0.1:" + server.getAddress().getPort(), 0.05);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.chatMessage(
                    "session", payload, new MockHttpServletResponse()))
                    .isInstanceOf(org.springframework.web.client.ResourceAccessException.class)
                    .hasCauseInstanceOf(java.net.SocketTimeoutException.class);
        } finally {
            server.stop(0);
        }
    }


    @Test public void browserCrudAndCancellationKeepTheOwnerAndResponseStatus() {
        ConversationService conversations=mock(ConversationService.class);
        AgentProxyService gateway=mock(AgentProxyService.class);
        LlmController controller=new LlmController(conversations,mock(AgentExecutionService.class),gateway,
                new ObjectMapper(),"http://py-app",new RestTemplate());
        Map<String,Object> input=new LinkedHashMap<>(); input.put("conversationId","thread"); input.put("runId","run");
        controller.conversationList(20); controller.conversationDetails("thread"); controller.conversationDelete(input);
        verify(conversations).conversations("{\"limit\":20,\"userId\":\"user-1\"}");
        verify(conversations).conversation("{\"conversationId\":\"thread\",\"userId\":\"user-1\"}");
        verify(conversations).deleteConversation("{\"conversationId\":\"thread\",\"runId\":\"run\",\"userId\":\"user-1\"}");
        when(gateway.cancel("cookie","thread","run")).thenReturn(new AgentResponse(409,"{}"));
        assertThat(controller.executionCancel("cookie",input).getStatusCodeValue()).isEqualTo(409);
        input.remove("runId");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.executionCancel("cookie",input)).isInstanceOf(IllegalArgumentException.class);
        input.put("runId","run"); input.put("conversationId",1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.executionCancel("cookie",input)).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.chatMessage("cookie",new byte[]{'?'},new MockHttpServletResponse()))
                .isInstanceOf(IllegalArgumentException.class);
        for (double timeout:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,Integer.MAX_VALUE})
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new LlmController(conversations,null,gateway,new ObjectMapper(),"http://py",timeout))
                    .isInstanceOf(IllegalArgumentException.class);
    }

    @Test public void browserWriteAndFlushFailuresCancelTheClaimedRun() throws Exception {
        for (boolean writeFailure:new boolean[]{true,false}) {
            AgentExecutionService executions=mock(AgentExecutionService.class); AgentProxyService gateway=mock(AgentProxyService.class);
            Map<String,Object> execution=new LinkedHashMap<>(); execution.put("conversationId","thread"); execution.put("runId","run");
            when(executions.claimAguiRun(org.mockito.Matchers.anyString())).thenReturn(java.util.Collections.singletonMap("data",execution));
            RestTemplate http=new RestTemplate(); MockRestServiceServer server=MockRestServiceServer.createServer(http);
            server.expect(requestTo("http://py/agent/v1/runs")).andRespond(withSuccess(writeFailure?"data: event\n\n":"",MediaType.TEXT_EVENT_STREAM));
            javax.servlet.http.HttpServletResponse response=mock(javax.servlet.http.HttpServletResponse.class);
            if (writeFailure) when(response.getOutputStream()).thenThrow(new java.io.IOException("closed"));
            else org.mockito.Mockito.doThrow(new java.io.IOException("closed")).when(response).flushBuffer();
            new LlmController(null,executions,gateway,new ObjectMapper(),"http://py",http).chatMessage("cookie",new byte[]{'{','}'},response);
            verify(gateway).cancel("cookie","thread","run"); server.verify();
        }
    }

}
