package com.union.control.service;

import com.union.control.controller.ApiExceptionHandler;
import com.union.control.controller.LlmController;

import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.mock.web.DelegatingServletOutputStream;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import javax.servlet.ServletOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyMap;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

public class LlmControllerTest {
    private ControlService service;
    private AgentProxyService proxy;
    private RestTemplate http;
    private MockRestServiceServer upstream;
    private MockMvc mvc;

    @Before
    public void setUp() {
        service = mock(ControlService.class);
        http = new RestTemplate();
        upstream = MockRestServiceServer.createServer(http);
        proxy = new AgentProxyService("http://py", "behavior-risk-token", http);
        when(service.claimAguiRun(anyString(), any(byte[].class)))
                .thenReturn(claimedExecution());
        mvc = standaloneSetup(new LlmController(service, proxy))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    public void springCanSelectTheProductionConstructor() {
        java.lang.reflect.Constructor<?>[] constructors = new AutowiredAnnotationBeanPostProcessor()
                .determineCandidateConstructors(LlmController.class, "llmController");
        assertNotNull(constructors);
        assertEquals(1, constructors.length);
        assertEquals(2, constructors[0].getParameterTypes().length);
    }

    @Test
    public void publicConversationRoutesEndInControl() throws Exception {
        when(service.conversations(anyString(), anyInt())).thenReturn(ok(Collections.emptyList()));
        when(service.conversation(anyString(), anyString())).thenReturn(ok(Collections.emptyMap()));
        when(service.rename(anyString(), anyMap())).thenReturn(ok(Collections.emptyMap()));
        when(service.deleteConversation(anyString(), anyMap())).thenReturn(ok(null));
        when(service.cancelExecution(anyString(), anyMap())).thenReturn(ok(null));

        mvc.perform(get("/llm/conversationList")).andExpect(status().isOk());
        mvc.perform(get("/llm/conversationDetails").param("conversationId", "conv-1")).andExpect(status().isOk());
        mvc.perform(post("/llm/conversationTitle").contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"conv-1\",\"title\":\"renamed\"}")).andExpect(status().isOk());
        mvc.perform(post("/llm/conversationDelete").contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"conv-1\"}")).andExpect(status().isOk());
        mvc.perform(get("/llm/executionCurrent")).andExpect(status().isNotFound());
        mvc.perform(post("/llm/executionCancel").contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"conv-1\",\"runId\":\"run-1\"}"))
                .andExpect(status().isOk());

        verify(service).conversation(LocalAuth.cookieHeader(), "conv-1");
        verify(service).cancelExecution(anyString(), anyMap());
    }

    @Test
    public void activeRunConflictDoesNotExposeARecoverableExecution() {
        ResponseEntity<Map<String, Object>> response = new ApiExceptionHandler()
                .activeExecution(new ControlService.ActiveExecutionException());

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("agent_run_active", response.getBody().get("errorCode"));
        assertTrue(!response.getBody().containsKey("activeExecution"));
    }

    @Test
    public void explicitCancellationStaysScopedThroughThePythonBoundary() throws Exception {
        when(service.cancelExecution(anyString(), anyMap())).thenReturn(claimedExecution());
        upstream.expect(once(), requestTo("http://py/agent/v1/runs/cancel"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header(HttpHeaders.COOKIE, LocalAuth.cookieHeader()))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .content().string(
                                "{\"conversationId\":\"thread-1\",\"runId\":\"run-1\"}"))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));

        mvc.perform(post("/llm/executionCancel").contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"thread-1\",\"runId\":\"run-1\"}"))
                .andExpect(status().isOk());

        upstream.verify();
    }

    @Test
    public void streamsAgentResponseAndInjectsControlIdentity() throws Exception {
        upstream.expect(once(), requestTo("http://py/agent/v1/runs"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header(HttpHeaders.COOKIE, LocalAuth.cookieHeader()))
                .andRespond(withSuccess("data: {\"content\":\"ok\"}\n\n", MediaType.TEXT_EVENT_STREAM));

        mvc.perform(post("/llm/chatMessage")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string("data: {\"content\":\"ok\"}\n\n"));
        upstream.verify();
    }

    @Test
    public void flushesEachUpstreamReadInsteadOfBufferingTheStream() throws Exception {
        byte[] body = new byte[5000];
        upstream.expect(once(), requestTo("http://py/agent/v1/runs"))
                .andRespond(withSuccess(body, MediaType.TEXT_EVENT_STREAM));
        final int[] flushes = {0};
        final ByteArrayOutputStream received = new ByteArrayOutputStream();
        MockHttpServletResponse response = new MockHttpServletResponse() {
            private final ServletOutputStream output = new DelegatingServletOutputStream(received) {
                @Override
                public void flush() throws java.io.IOException {
                    flushes[0]++;
                    super.flush();
                }
            };

            @Override
            public ServletOutputStream getOutputStream() {
                return output;
            }
        };

        new LlmController(service, proxy)
                .chatMessage("{}".getBytes("UTF-8"), response);

        assertEquals(body.length, received.size());
        assertTrue(flushes[0] > 1);
        upstream.verify();
    }

    @Test
    public void clientDisconnectCancelsTheClaimedRunAndUpstream() throws Exception {
        upstream.expect(once(), requestTo("http://py/agent/v1/runs"))
                .andRespond(withSuccess(new byte[32], MediaType.TEXT_EVENT_STREAM));
        upstream.expect(once(), requestTo("http://py/agent/v1/runs/cancel"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));
        MockHttpServletResponse response = new MockHttpServletResponse() {
            private final ServletOutputStream output = new DelegatingServletOutputStream(
                    new ByteArrayOutputStream()) {
                @Override
                public void write(byte[] value, int offset, int length) throws IOException {
                    throw new IOException("browser disconnected");
                }
            };

            @Override
            public ServletOutputStream getOutputStream() {
                return output;
            }
        };

        new LlmController(service, proxy)
                .chatMessage("{}".getBytes("UTF-8"), response);

        verify(service).cancelExecution(
                LocalAuth.cookieHeader(), "thread-1", "run-1", "client_disconnected");
        upstream.verify();
    }

    @Test
    public void upstreamStartFailureTerminatesTheClaimedRun() throws Exception {
        upstream.expect(once(), requestTo("http://py/agent/v1/runs"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.BAD_GATEWAY));

        mvc.perform(post("/llm/chatMessage")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadGateway());

        verify(service).failExecution(
                LocalAuth.cookieHeader(), "thread-1", "run-1", "agent_start_failed");
        upstream.verify();
    }

    @Test
    public void publicValidationErrorsKeepTheControlEnvelope() throws Exception {
        when(service.rename(anyString(), anyMap())).thenThrow(new IllegalArgumentException("title 非法"));

        mvc.perform(post("/llm/conversationTitle").contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"conv-1\",\"title\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"success\":false,\"errorMsg\":\"title 非法\"}"));
    }

    @Test
    public void forwardsControlSessionForGeneralSyncAndTokenForFixedScenario() throws Exception {
        upstream.expect(once(), requestTo("http://py/agent/v1/runs/sync"))
                .andExpect(header(HttpHeaders.COOKIE, LocalAuth.cookieHeader()))
                .andExpect(request -> assertNull(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)))
                .andRespond(withSuccess("{\"content\":{}}", MediaType.APPLICATION_JSON));
        upstream.expect(once(), requestTo("http://py/agent/v1/scenarios/behavior-risk/runs"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer behavior-risk-token"))
                .andExpect(request -> assertNull(request.getHeaders().getFirst(HttpHeaders.COOKIE)))
                .andRespond(withSuccess("{\"content\":{}}", MediaType.APPLICATION_JSON));

        mvc.perform(post("/llm/chatMessageSync")
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"q\",\"input\":{}}"))
                .andExpect(status().isOk());
        mvc.perform(post("/llm/behaviorRisk").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        upstream.verify();
    }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", data);
        return result;
    }

    private static Map<String, Object> claimedExecution() {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("conversationId", "thread-1");
        execution.put("runId", "run-1");
        return ok(execution);
    }
}
