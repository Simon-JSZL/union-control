package com.epcc.arkweb.web.llm;

import com.epcc.arkweb.helper.AuthenticatedRequest;
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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

public class LlmControllerTest {
    @Test
    public void returnsSynchronousAgentResponseAsJson() {
        ConversationService conversations = mock(ConversationService.class);
        AgentExecutionService executions = mock(AgentExecutionService.class);
        AgentProxyService gateway = mock(AgentProxyService.class);
        AuthenticatedRequest request = mock(AuthenticatedRequest.class);
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        when(request.clientPayload(payload)).thenReturn(payload);
        when(gateway.sync("CASSESSIONID=session", "{}"))
                .thenReturn(new AgentResponse(200, "{\"content\":{\"status\":\"READY\"}}"));

        ResponseEntity<String> response = new LlmController(
                conversations, executions, gateway, request, "http://py-app", new RestTemplate())
                .chatMessageSync("CASSESSIONID=session", payload);

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isEqualTo("{\"content\":{\"status\":\"READY\"}}");
    }

    @Test
    public void streamsPyAppHttpResponseDirectlyToTheBrowser() throws Exception {
        ConversationService conversations = mock(ConversationService.class);
        AgentExecutionService executions = mock(AgentExecutionService.class);
        AgentProxyService gateway = mock(AgentProxyService.class);
        AuthenticatedRequest request = mock(AuthenticatedRequest.class);
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        when(request.clientPayload(payload)).thenReturn(payload);
        when(request.json(payload)).thenReturn("claim");
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("conversationId", "conversation-1");
        execution.put("runId", "run-1");
        Map<String, Object> claimed = new LinkedHashMap<>();
        claimed.put("data", execution);
        when(executions.claimAguiRun("claim")).thenReturn(claimed);
        server.expect(requestTo("http://py-app/agent/v1/runs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.COOKIE, "CASSESSIONID=session"))
                .andRespond(withSuccess("data: first\n\ndata: second\n\n", MediaType.TEXT_EVENT_STREAM));
        MockHttpServletResponse response = new MockHttpServletResponse();

        new LlmController(conversations, executions, gateway, request, "http://py-app", http)
                .chatMessage("CASSESSIONID=session", payload, response);

        assertThat(response.getContentAsString()).isEqualTo("data: first\n\ndata: second\n\n");
        assertThat(response.getHeader(HttpHeaders.CONTENT_TYPE)).isEqualTo("text/event-stream");
        assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no");
        server.verify();
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
            AuthenticatedRequest request = mock(AuthenticatedRequest.class);
            AgentExecutionService executions = mock(AgentExecutionService.class);
            byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
            when(request.clientPayload(payload)).thenReturn(payload);
            when(request.json(payload)).thenReturn("claim");
            Map<String, Object> execution = new LinkedHashMap<>();
            execution.put("conversationId", "thread-1");
            execution.put("runId", "run-1");
            when(executions.claimAguiRun("claim"))
                    .thenReturn(java.util.Collections.singletonMap("data", execution));
            LlmController controller = new LlmController(mock(ConversationService.class), executions,
                    mock(AgentProxyService.class), request,
                    "http://127.0.0.1:" + server.getAddress().getPort(), 0.05);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.chatMessage(
                    "session", payload, new MockHttpServletResponse()))
                    .isInstanceOf(org.springframework.web.client.ResourceAccessException.class)
                    .hasCauseInstanceOf(java.net.SocketTimeoutException.class);
        } finally {
            server.stop(0);
        }
    }

}
