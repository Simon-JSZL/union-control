package com.epcc.arkweb.web.llm;

import com.union.control.service.ScheduledTaskService;
import org.junit.After;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AgentAuthorizationInterceptorTest {
    private static final String TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @After
    public void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    public void rawScheduledBearerCannotSkipTheAuthorizationEndpoint() throws Exception {
        AgentAuthorizationInterceptor interceptor = interceptor(mock(ScheduledTaskService.class));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agent/tool");
        request.addHeader("Authorization", "Scheduled " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, handler())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    public void trustedContextUsesDatabaseIdentityAndLiveResources() throws Exception {
        ScheduledTaskService tasks = mock(ScheduledTaskService.class);
        when(tasks.resolveScheduledToken(TOKEN)).thenReturn(row());
        AgentAuthorizationInterceptor interceptor = interceptor(tasks);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agent/tool");
        request.addHeader(AgentAuthorizationInterceptor.TRUSTED_CONTEXT_HEADER,
                "Scheduled " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(interceptor.preHandle(request, response, handler())).isTrue();
        assertThat(AgentAuthorizationInterceptor.currentContext().get("userId"))
                .isEqualTo("user-1");
        assertThat(AgentAuthorizationInterceptor.currentContext().get("roleId"))
                .isEqualTo("role-1");
    }

    @Test
    public void missingProductionAuthServiceFailsClosed() throws Exception {
        ScheduledTaskService tasks = mock(ScheduledTaskService.class);
        when(tasks.resolveScheduledToken(TOKEN)).thenReturn(row());
        AgentAuthorizationInterceptor interceptor = new AgentAuthorizationInterceptor(tasks);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agent/tool");
        request.addHeader(AgentAuthorizationInterceptor.TRUSTED_CONTEXT_HEADER,
                "Scheduled " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, handler())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    public void unannotatedAgentMethodFailsClosed() throws Exception {
        AgentAuthorizationInterceptor interceptor = interceptor(mock(ScheduledTaskService.class));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agent/newTool");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Method method = Probe.class.getMethod("unprotectedTool");

        assertThat(interceptor.preHandle(
                request, response, new HandlerMethod(new Probe(), method))).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    private static AgentAuthorizationInterceptor interceptor(ScheduledTaskService tasks) {
        AgentAuthorizationInterceptor interceptor = new AgentAuthorizationInterceptor(tasks);
        interceptor.setArkAuthService(new FakeArkAuthService());
        return interceptor;
    }

    private static HandlerMethod handler() throws Exception {
        Method method = Probe.class.getMethod("tool");
        return new HandlerMethod(new Probe(), method);
    }

    private static Map<String, Object> row() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("runId", 7L);
        row.put("taskId", 8L);
        row.put("userId", "user-1");
        row.put("orgCode", "org-1");
        row.put("roleId", "role-1");
        row.put("prompt", "prompt");
        row.put("scheduledAt", "2026-09-02T01:00:00.000Z");
        row.put("timezone", "UTC");
        row.put("expiresAt", "2030-09-02T02:00:00.000Z");
        return row;
    }

    public static final class Probe {
        @AgentPermission("/assistantManager/page")
        public void tool() {}
        public void unprotectedTool() {}
    }

    public static final class FakeArkAuthService {
        public FakeResult queryResource(String roleId, String userName, String traceNo) {
            return new FakeResult(Collections.singletonList(
                    new FakeResource("/assistantManager/page")));
        }
    }

    public static final class FakeResult {
        private final Object result;
        FakeResult(Object result) { this.result = result; }
        public boolean isSuccess() { return true; }
        public Object getResult() { return result; }
    }

    public static final class FakeResource {
        private final String url;
        FakeResource(String url) { this.url = url; }
        public String getResourceUrl() { return url; }
    }
}
