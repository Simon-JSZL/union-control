package com.epcc.arkweb.web.llm;

import com.union.control.service.ScheduledTaskService;
import org.junit.After;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;
import com.fasterxml.jackson.databind.ObjectMapper;

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
        org.apache.shiro.util.ThreadContext.remove();
    }

    @Test
    public void unknownScheduledTokenIsRejected() throws Exception {
        AgentAuthorizationInterceptor interceptor = interceptor(mock(ScheduledTaskService.class));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agent/tool");
        request.addHeader("Authorization", "Scheduled " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(allowed(interceptor, request, response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(new ObjectMapper().readTree(response.getContentAsString()))
                .isEqualTo(new ObjectMapper().readTree(
                        "{\"success\":false,\"msg\":\"scheduled_auth_error\",\"code\":1,\"data\":null}"));
    }

    @Test
    public void scheduledTokenLoadsDatabaseIdentityAndSavedPermissions() throws Exception {
        ScheduledTaskService tasks = mock(ScheduledTaskService.class);
        when(tasks.resolveScheduledToken(TOKEN)).thenReturn(row());
        AgentAuthorizationInterceptor interceptor = interceptor(tasks);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agent/tool");
        request.addHeader("Authorization",
                "Scheduled " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(allowed(interceptor, request, response)).isTrue();
        assertThat(AgentAuthorizationInterceptor.currentContext().get("userId"))
                .isEqualTo("user-1");
        assertThat(AgentAuthorizationInterceptor.currentContext().get("roleId"))
                .isEqualTo("role-1");
    }

    @Test
    public void missingPermissionSnapshotFailsClosed() throws Exception {
        ScheduledTaskService tasks = mock(ScheduledTaskService.class);
        Map<String, Object> missing = row();
        missing.remove("permissionSnapshot");
        when(tasks.resolveScheduledToken(TOKEN)).thenReturn(missing);
        AgentAuthorizationInterceptor interceptor = interceptor(tasks);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agent/tool");
        request.addHeader("Authorization",
                "Scheduled " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(allowed(interceptor, request, response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    public void rejectsMixedDuplicateAndMalformedCredentialsBeforeLookup() throws Exception {
        ScheduledTaskService tasks = mock(ScheduledTaskService.class);
        AgentAuthorizationInterceptor filter = interceptor(tasks);
        for (String type : new String[]{"cookie", "duplicate", "malformed"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agent/tool");
            request.addHeader("Authorization", "malformed".equals(type) ? "Scheduled bad" : "Scheduled " + TOKEN);
            if ("cookie".equals(type)) request.addHeader("Cookie", "CASSESSIONID=session");
            if ("duplicate".equals(type)) request.addHeader("Authorization", "Scheduled " + TOKEN);
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(allowed(filter, request, response)).isFalse();
            assertThat(response.getStatus()).isEqualTo("malformed".equals(type) ? 401 : 400);
        }
        org.mockito.Mockito.verifyZeroInteractions(tasks);
    }

    @Test
    public void eachToolCallRechecksTheRunAndExpiry() throws Exception {
        ScheduledTaskService tasks = mock(ScheduledTaskService.class);
        Map<String, Object> expired = row();
        expired.put("expiresAt", "2020-01-01T00:00:00Z");
        when(tasks.resolveScheduledToken(TOKEN)).thenReturn(row(), null, expired);
        AgentAuthorizationInterceptor filter = interceptor(tasks);
        for (boolean expected : new boolean[]{true, false, false}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agent/tool");
            request.addHeader("Authorization", "Scheduled " + TOKEN);
            assertThat(allowed(filter, request, new MockHttpServletResponse())).isEqualTo(expected);
        }
        org.mockito.Mockito.verify(tasks, org.mockito.Mockito.times(3)).resolveScheduledToken(TOKEN);
    }

    @Test
    public void missingCredentialsAndRemovedHeaderCannotAuthorizeAnAgentRequest() throws Exception {
        org.apache.shiro.subject.Subject subject = mock(org.apache.shiro.subject.Subject.class);
        org.apache.shiro.util.ThreadContext.bind(subject);
        for (boolean oldHeader : new boolean[]{false, true}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agent/newTool");
            if (oldHeader) request.addHeader("X-Agent-Trusted-Context", "Scheduled " + TOKEN);
            assertThat(allowed(interceptor(mock(ScheduledTaskService.class)), request,
                    new MockHttpServletResponse())).isFalse();
        }
        when(subject.isAuthenticated()).thenReturn(true);
        org.mockito.Mockito.doThrow(new org.apache.shiro.authz.UnauthorizedException())
                .when(subject).checkPermission("/assistantManager/page");
        assertThat(allowed(interceptor(mock(ScheduledTaskService.class)),
                new MockHttpServletRequest("POST", "/agent/newTool"), new MockHttpServletResponse())).isFalse();
        org.mockito.Mockito.verify(subject).checkPermission("/assistantManager/page");
    }

    private static AgentAuthorizationInterceptor interceptor(ScheduledTaskService tasks) {
        AgentAuthorizationInterceptor interceptor = new AgentAuthorizationInterceptor(tasks, new ObjectMapper());
        return interceptor;
    }

    private static boolean allowed(AgentAuthorizationInterceptor filter,
            MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        return filter.preHandle(request, response, new HandlerMethod(new Endpoint(),
                Endpoint.class.getMethod("tool")));
    }

    private static Map<String, Object> row() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("permissionSnapshot", "[\"/assistantManager/page\"]");
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

    public static class Endpoint {
        @AgentPermission("/assistantManager/page")
        public void tool() {}
        public void unguarded() {}
        @AgentPermission(" ") public void emptyPermission() {}
    }

    @Test
    public void missingAnnotationAndNonHandlerAreDenied() throws Exception {
        AgentAuthorizationInterceptor guard = interceptor(mock(ScheduledTaskService.class));
        for (Object handler : new Object[]{new Object(),
                new HandlerMethod(new Endpoint(), Endpoint.class.getMethod("unguarded")),
                new HandlerMethod(new Endpoint(), Endpoint.class.getMethod("emptyPermission"))}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(guard.preHandle(new MockHttpServletRequest(), response, handler)).isFalse();
            assertThat(response.getStatus()).isEqualTo(403);
        }
    }

    @Test public void invalidSnapshotsAndSavedIdentityFailClosed() throws Exception {
        ScheduledTaskService tasks=mock(ScheduledTaskService.class); AgentAuthorizationInterceptor guard=interceptor(tasks);
        for (Object snapshot:new Object[]{null,12,"","null","{}","[1]","[]","[\"/other\"]","bad"}) {
            Map<String,Object> identity=row(); identity.put("permissionSnapshot",snapshot);
            when(tasks.resolveScheduledToken(TOKEN)).thenReturn(identity);
            MockHttpServletRequest request=new MockHttpServletRequest(); request.addHeader("Authorization","Scheduled "+TOKEN);
            assertThat(allowed(guard,request,new MockHttpServletResponse())).isFalse();
        }
        for (String key:new String[]{"runId","taskId","userId","prompt"}) {
            for (Object invalid:new Object[]{null,0,"", " "}) {
                Map<String,Object> identity=row(); identity.put(key,invalid);
                when(tasks.resolveScheduledToken(TOKEN)).thenReturn(identity);
                MockHttpServletRequest request=new MockHttpServletRequest(); request.addHeader("Authorization","Scheduled "+TOKEN);
                assertThat(allowed(guard,request,new MockHttpServletResponse())).isFalse();
            }
        }
        Map<String,Object> identity=row(); identity.put("userId",String.join("",Collections.nCopies(65,"x")));
        when(tasks.resolveScheduledToken(TOKEN)).thenReturn(identity);
        MockHttpServletRequest request=new MockHttpServletRequest(); request.addHeader("Authorization","Scheduled "+TOKEN);
        assertThat(allowed(guard,request,new MockHttpServletResponse())).isFalse();
        for (String value:new String[]{"Bearer token","Scheduled "+String.join("",Collections.nCopies(43,"!"))}) {
            request=new MockHttpServletRequest(); request.addHeader("Authorization",value);
            assertThat(allowed(guard,request,new MockHttpServletResponse())).isFalse();
        }
    }

    @Test public void casErrorsAndClosedResponsesNeverPermitTheRequest() throws Exception {
        org.apache.shiro.subject.Subject subject=mock(org.apache.shiro.subject.Subject.class);
        org.apache.shiro.util.ThreadContext.bind(subject);
        when(subject.isAuthenticated()).thenThrow(new IllegalStateException("unavailable"));
        MockHttpServletResponse response=new MockHttpServletResponse();
        assertThat(allowed(interceptor(mock(ScheduledTaskService.class)),new MockHttpServletRequest(),response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        javax.servlet.http.HttpServletResponse closed=mock(javax.servlet.http.HttpServletResponse.class);
        when(closed.getWriter()).thenThrow(new java.io.IOException("closed"));
        assertThat(interceptor(mock(ScheduledTaskService.class)).preHandle(new MockHttpServletRequest(),closed,new Object())).isFalse();
        org.mockito.Mockito.verify(closed).setStatus(403);
        javax.servlet.http.HttpServletRequest request=mock(javax.servlet.http.HttpServletRequest.class);
        assertThat(interceptor(mock(ScheduledTaskService.class)).preHandle(request,new MockHttpServletResponse(),new HandlerMethod(new Endpoint(),Endpoint.class.getMethod("tool")))).isFalse();
    }

}
