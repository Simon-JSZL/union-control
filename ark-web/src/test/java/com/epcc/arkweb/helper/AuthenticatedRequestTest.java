package com.epcc.arkweb.helper;

import com.epcc.arkweb.ShiroTestSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.epcc.arkweb.web.llm.AgentAuthorizationInterceptor;
import com.union.control.service.ScheduledTaskService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AuthenticatedRequestTest {
    private final ObjectMapper json = new ObjectMapper();

    @Before
    public void setUp() { ShiroTestSupport.bindLocalUser(); }

    @After
    public void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        ShiroTestSupport.clear();
    }

    @Test
    public void authenticatedIdentityOverridesClientSuppliedIdentity() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", "attacker");
        payload.put("orgCode", "attacker-org");
        payload.put("roleId", "attacker-role");
        payload.put("conversationId", "thread-1");

        String input = new AuthenticatedRequest(json).json(payload);
        Map<String, Object> parsed = json.readValue(
                input, new TypeReference<Map<String, Object>>() {});

        assertThat(parsed)
                .containsEntry("userId", ShiroTestSupport.USER_ID)
                .containsEntry("orgCode", ShiroTestSupport.ORG_CODE)
                .containsEntry("roleId", ShiroTestSupport.ROLE_ID)
                .containsEntry("conversationId", "thread-1");
    }

    @Test
    public void removesClientIdentityBeforeForwardingPayload() throws Exception {
        byte[] input = "{\"userId\":\"attacker\",\"orgCode\":\"x\",\"prompt\":\"hello\"}"
                .getBytes("UTF-8");

        Map<String, Object> parsed = json.readValue(
                new AuthenticatedRequest(json).clientPayload(input),
                new TypeReference<Map<String, Object>>() {});

        assertThat(parsed).containsEntry("prompt", "hello")
                .doesNotContainKeys("userId", "orgCode", "roleId");
    }

    @Test
    public void scheduledContextOverridesClientIdentityWithoutUsingShiro() throws Exception {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("runId", 7L);
        context.put("taskId", 8L);
        context.put("userId", "scheduled-user");
        context.put("orgCode", "scheduled-org");
        context.put("roleId", "scheduled-role");
        context.put("prompt", "prompt");
        context.put("scheduledAt", "2026-09-02T01:00:00.000Z");
        context.put("timezone", "UTC");
        context.put("expiresAt", "2030-09-02T02:00:00.000Z");
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        ScheduledTaskService tasks = mock(ScheduledTaskService.class);
        when(tasks.resolveScheduledToken(
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")).thenReturn(context);
        AgentAuthorizationInterceptor authorization = new AgentAuthorizationInterceptor(tasks);
        authorization.setArkAuthService(new FakeArkAuthService());
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        servletRequest.addHeader(AgentAuthorizationInterceptor.TRUSTED_CONTEXT_HEADER,
                "Scheduled AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));
        authorization.preHandle(servletRequest, servletResponse, scheduledHandler());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", "attacker");
        payload.put("orgCode", "attacker-org");
        payload.put("roleId", "attacker-role");

        Map<String, Object> parsed = json.readValue(
                new AuthenticatedRequest(json).json(payload),
                new TypeReference<Map<String, Object>>() {});

        assertThat(parsed).containsEntry("userId", "scheduled-user")
                .containsEntry("orgCode", "scheduled-org")
                .containsEntry("roleId", "scheduled-role");
    }

    private static org.springframework.web.method.HandlerMethod scheduledHandler() throws Exception {
        return new org.springframework.web.method.HandlerMethod(new ScheduledProbe(),
                ScheduledProbe.class.getMethod("tool"));
    }

    public static final class ScheduledProbe {
        @com.epcc.arkweb.web.llm.AgentPermission("/assistantManager/page")
        public void tool() {}
    }

    public static final class FakeArkAuthService {
        public FakeResult queryResource(String roleId, String userName, String traceNo) {
            return new FakeResult(java.util.Collections.singletonList(new FakeResource()));
        }
    }

    public static final class FakeResult {
        private final Object result;
        FakeResult(Object result) { this.result = result; }
        public boolean isSuccess() { return true; }
        public Object getResult() { return result; }
    }

    public static final class FakeResource {
        public String getResourceUrl() { return "/assistantManager/page"; }
    }
}
