package com.epcc.arkweb.web.llm;

import com.epcc.arkweb.helper.AuthenticatedRequest;
import com.union.control.service.AgentExecutionService;
import com.union.control.service.ConversationService;
import com.union.control.service.MemoryStoreService;
import com.union.control.service.RunningAnalysisMockService;
import com.union.control.service.ScheduledTaskService;
import org.junit.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AgentControllerAuthorizationTest {
    @Test
    public void returnsTrustedContextOnlyAfterControlAndResourceChecks() {
        ScheduledTaskService tasks = mock(ScheduledTaskService.class);
        String token = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
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
        when(tasks.resolveScheduledToken(token)).thenReturn(row);
        AgentAuthorizationInterceptor authorization = new AgentAuthorizationInterceptor(tasks);
        authorization.setArkAuthService(new FakeArkAuthService(true));
        AgentController controller = new AgentController(
                mock(ConversationService.class), mock(AgentExecutionService.class),
                mock(MemoryStoreService.class), mock(RunningAnalysisMockService.class),
                mock(AuthenticatedRequest.class), authorization);

        ResponseEntity<Map<String, Object>> response = controller.scheduledTaskAuthorize(
                "Scheduled " + token, Collections.emptyMap());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("success")).isEqualTo(true);
        @SuppressWarnings("unchecked") Map<String, Object> data =
                (Map<String, Object>) response.getBody().get("data");
        assertThat(data).containsEntry("userId", "user-1")
                .containsEntry("roleId", "role-1")
                .containsEntry("trustedContext", "Scheduled " + token);
    }

    @Test
    public void rejectsIdentityFieldsInTheAuthorizationRequestBody() {
        AgentAuthorizationInterceptor authorization = new AgentAuthorizationInterceptor(
                mock(ScheduledTaskService.class));
        authorization.setArkAuthService(new FakeArkAuthService(true));
        AgentController controller = new AgentController(
                mock(ConversationService.class), mock(AgentExecutionService.class),
                mock(MemoryStoreService.class), mock(RunningAnalysisMockService.class),
                mock(AuthenticatedRequest.class), authorization);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("roleId", "attacker-role");

        assertThat(controller.scheduledTaskAuthorize(
                "Scheduled AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", payload)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    public static final class FakeArkAuthService {
        private final boolean allowed;

        FakeArkAuthService(boolean allowed) { this.allowed = allowed; }

        public FakeResult queryResource(String roleId, String userName, String traceNo) {
            return new FakeResult(allowed
                    ? Collections.singletonList(new FakeResource("/assistantManager/page"))
                    : Collections.<FakeResource>emptyList());
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
