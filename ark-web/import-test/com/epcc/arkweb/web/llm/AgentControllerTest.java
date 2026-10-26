package com.epcc.arkweb.web.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.epcc.arkweb.utils.ResultMsg;
import com.epcc.arkweb.model.ShiroUser;
import com.union.control.service.*;
import org.junit.Test;
import org.junit.After;
import org.apache.shiro.authz.UnauthorizedException;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.springframework.aop.framework.ProxyFactory;
import com.epcc.arkweb.config.ShiroConfig;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class AgentControllerTest {
    private static final String TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @Test
    public void returnsIdentityWithoutCredentialAndChecksAuthorizationOnlyOnce() throws Exception {
        ScheduledTaskService tasks = mock(ScheduledTaskService.class);
        when(tasks.resolveScheduledToken(TOKEN)).thenReturn(row());
        mvc(tasks, true).perform(post("/agent/scheduledTaskAuthorize")
                .header("Authorization", "Scheduled " + TOKEN)
                .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("操作成功"))
                .andExpect(jsonPath("$.data.userId").value("user-1"))
                .andExpect(jsonPath("$.data.roleId").value("role-1"))
                .andExpect(jsonPath("$.data.trustedContext").doesNotExist())
                .andExpect(jsonPath("$.data.permissions").doesNotExist());
        verify(tasks).resolveScheduledToken(TOKEN);
    }

    @Test
    public void rejectsInjectedIdentityAndMissingSavedPermission() throws Exception {
        ScheduledTaskService tasks = mock(ScheduledTaskService.class);
        when(tasks.resolveScheduledToken(TOKEN)).thenReturn(row());
        mvc(tasks, true).perform(post("/agent/scheduledTaskAuthorize")
                .header("Authorization", "Scheduled " + TOKEN)
                .contentType("application/json").content("{\"roleId\":\"attacker\"}"))
                .andExpect(status().isUnauthorized());
        mvc(tasks, false).perform(post("/agent/scheduledTaskAuthorize")
                .header("Authorization", "Scheduled " + TOKEN)
                .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @After
    public void clearSubject() { ThreadContext.remove(); }

    @Test
    public void scheduledRequestsCheckEachMethodsExactPermission() throws Exception {
        ScheduledTaskService tasks = mock(ScheduledTaskService.class);
        Map<String, Object> saved = row();
        saved.put("permissionSnapshot", "[\"/operations/read\"]");
        when(tasks.resolveScheduledToken(TOKEN)).thenReturn(saved);
        MockMvc mvc = mvc(tasks, new PermissionController());
        mvc.perform(post("/agent/getOrgInfo").header("Authorization", "Scheduled " + TOKEN))
                .andExpect(status().isOk());
        mvc.perform(post("/agent/queryChanges").header("Authorization", "Scheduled " + TOKEN))
                .andExpect(status().isForbidden());
        saved.put("permissionSnapshot", "[\"/operations/*\"]");
        mvc.perform(post("/agent/getOrgInfo").header("Authorization", "Scheduled " + TOKEN))
                .andExpect(status().isForbidden());
        verify(tasks, times(3)).resolveScheduledToken(TOKEN);
    }

    @Test
    public void loggedInRequestsKeepNativeShiroMethodPermissionChecks() throws Exception {
        Subject subject = mock(Subject.class);
        ThreadContext.bind(subject);
        when(subject.isAuthenticated()).thenReturn(true);
        doThrow(new UnauthorizedException()).when(subject).checkPermission("/operations/write");
        ScheduledTaskService tasks = mock(ScheduledTaskService.class);
        MockMvc mvc = mvc(tasks, new PermissionController());
        mvc.perform(post("/agent/getOrgInfo")).andExpect(status().isOk());
        mvc.perform(post("/agent/queryChanges")).andExpect(status().isForbidden());
        verify(subject).checkPermission("/operations/read");
        verify(subject).checkPermission("/operations/write");
        verifyZeroInteractions(tasks);
    }

    @Test
    public void memoryWritesUseOnlyTheRequiredOwnerForCasAndScheduledRequests() throws Exception {
        for (boolean scheduled : new boolean[]{false, true}) {
            ThreadContext.remove();
            if (!scheduled) {
                Subject subject = mock(Subject.class);
                ShiroUser user = new ShiroUser();
                user.setLoginName("user-1");
                when(subject.isAuthenticated()).thenReturn(true);
                when(subject.getPrincipal()).thenReturn(user);
                ThreadContext.bind(subject);
            }
            ScheduledTaskService tasks = mock(ScheduledTaskService.class);
            Map<String, Object> identity = row();
            identity.put("userId", "scheduled-user");
            when(tasks.resolveScheduledToken(TOKEN)).thenReturn(identity);
            MemoryStoreService memory = mock(MemoryStoreService.class);
            when(memory.memoryWrite(anyString())).thenReturn(Collections.singletonMap("success", true));
            AgentController controller = new AgentController(null, null, memory, null, new ObjectMapper());
            MockMvc mvc = mvc(tasks, controller);
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request =
                    post("/agent/memoryStore/write").contentType("application/json")
                            .content("{\"userId\":\"forged\",\"path\":\"note\",\"content\":\"hello\"}");
            if (scheduled) request.header("Authorization", "Scheduled " + TOKEN);
            mvc.perform(request).andExpect(status().isOk());
            verify(memory).memoryWrite("{\"userId\":\"" + (scheduled ? "scheduled-user" : "user-1")
                    + "\",\"path\":\"note\",\"content\":\"hello\"}");
            if (!scheduled) verifyZeroInteractions(tasks);
        }
    }

    @Test
    public void everyAgentEndpointDeclaresItsPermission() {
        for (java.lang.reflect.Method method : AgentController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(PostMapping.class))
                assertThat(method.getAnnotation(AgentPermission.class)).as(method.getName()).isNotNull();
        }
    }

    @Test
    public void mapsServiceStatusAndPayloadWithoutDoubleWrapping() throws Exception {
        com.epcc.arkweb.ShiroTestSupport.bindLocalUser();
        ConversationService conversations = mock(ConversationService.class);
        MemoryStoreService memory = mock(MemoryStoreService.class);
        RunningAnalysisMockService tools = mock(RunningAnalysisMockService.class);
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("success", true);
        history.put("messages", Collections.singletonList("stored-message"));
        when(conversations.conversationMessages(anyString())).thenReturn(history);
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("success", true);
        query.put("data", Collections.singletonList("row"));
        when(tools.queryBigData(anyString())).thenReturn(query);
        Map<String, Object> conflict = new LinkedHashMap<>();
        conflict.put("success", false);
        conflict.put("errorCode", "version_conflict");
        conflict.put("errorMsg", "memory version 已变化");
        when(memory.memoryWrite(anyString())).thenReturn(conflict);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new AgentController(conversations, null, memory, tools, new ObjectMapper())).build();
        mvc.perform(post("/agent/getConversationMessages").contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages[0]").value("stored-message"))
                .andExpect(jsonPath("$.data.success").doesNotExist());
        mvc.perform(post("/agent/queryBigData").contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("row"));
        mvc.perform(post("/agent/memoryStore/write").contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.msg").value("memory version 已变化"))
                .andExpect(jsonPath("$.data.errorCode").value("version_conflict"))
                .andExpect(jsonPath("$.data.errorMsg").doesNotExist());
        assertThat(history).containsEntry("success", true);
        assertThat(conflict).containsEntry("errorMsg", "memory version 已变化");
    }

    @RequestMapping("/agent")
    public static class PermissionController extends AgentController {
        public PermissionController() { super(null, null, null, null, null); }
        @Override
        @PostMapping("/getOrgInfo")
        @AgentPermission("/operations/read")
        public ResultMsg getOrgInfo(Map<String, Object> payload) { return ResultMsg.ok(Collections.emptyMap()); }
        @Override
        @PostMapping("/queryChanges")
        @AgentPermission("/operations/write")
        public ResultMsg queryChanges(Map<String, Object> payload) { return ResultMsg.ok(Collections.emptyMap()); }
    }

    private static MockMvc mvc(ScheduledTaskService tasks, boolean permitted) {
        AgentController controller = new AgentController(mock(ConversationService.class),
                mock(AgentExecutionService.class), mock(MemoryStoreService.class),
                mock(RunningAnalysisMockService.class), new ObjectMapper());
        if (!permitted) {
            Map<String, Object> saved = row();
            saved.put("permissionSnapshot", "[]");
            when(tasks.resolveScheduledToken(TOKEN)).thenReturn(saved);
        }
        return mvc(tasks, controller);
    }

    private static MockMvc mvc(ScheduledTaskService tasks, AgentController controller) {
        ProxyFactory proxy = new ProxyFactory(controller);
        proxy.setProxyTargetClass(true);
        proxy.addAdvisor(new ShiroConfig().authorizationAttributeSourceAdvisor(null));
        return MockMvcBuilders.standaloneSetup(proxy.getProxy())
                .addInterceptors(new AgentAuthorizationInterceptor(tasks, new ObjectMapper())).build();
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


    @Test public void delegatesOwnerScopedApisAndDomainToolsWithoutChangingTheirContracts() throws Exception {
        com.epcc.arkweb.ShiroTestSupport.bindLocalUser();
        ConversationService conversations=mock(ConversationService.class);
        AgentExecutionService executions=mock(AgentExecutionService.class);
        MemoryStoreService memory=mock(MemoryStoreService.class);
        RunningAnalysisMockService tools=mock(RunningAnalysisMockService.class);
        Map<String, Object> result = Collections.singletonMap("success", true);
        when(conversations.conversationMessages(anyString())).thenReturn(result);
        when(executions.completeRun(anyString())).thenReturn(result);
        when(memory.memoryRead(anyString())).thenReturn(result);
        when(memory.memoryList(anyString())).thenReturn(result);
        when(memory.memoryOperation(anyString())).thenReturn(result);
        when(memory.memoryWrite(anyString())).thenReturn(result);
        when(memory.memoryDelete(anyString())).thenReturn(result);
        when(memory.memorySearch(anyString())).thenReturn(result);
        when(tools.getOrgInfo(anyString())).thenReturn(result);
        when(tools.queryBigData(anyString())).thenReturn(result);
        when(tools.queryChanges(anyString())).thenReturn(result);
        when(tools.queryFaults(anyString())).thenReturn(result);
        AgentController controller=new AgentController(conversations,executions,memory,tools,new ObjectMapper());
        Map<String,Object> input=new LinkedHashMap<>(); input.put("userId","forged");
        controller.messages(input); controller.completeRun(input);
        controller.memoryRead(input); controller.memoryList(input); controller.memoryOperation(input);
        controller.memoryWrite(input); controller.memoryDelete(input); controller.memorySearch(input);
        String owned="{\"userId\":\"user-1\"}";
        verify(conversations).conversationMessages(owned); verify(executions).completeRun(owned);
        verify(memory).memoryRead(owned); verify(memory).memoryList(owned); verify(memory).memoryOperation(owned);
        verify(memory).memoryWrite(owned); verify(memory).memoryDelete(owned); verify(memory).memorySearch(owned);
        controller.getOrgInfo(input); controller.queryBigData(input); controller.queryChanges(input); controller.queryFaults(input);
        verify(tools).getOrgInfo("{\"userId\":\"forged\"}"); verify(tools).queryBigData("{\"userId\":\"forged\"}");
        verify(tools).queryChanges("{\"userId\":\"forged\"}"); verify(tools).queryFaults("{\"userId\":\"forged\"}");
        assertThat(controller.permissionDenied().getStatusCodeValue()).isEqualTo(403);
        assertThat(controller.queryError(new RunningAnalysisMockService.QueryException("unsupported",422)).getStatusCodeValue()).isEqualTo(422);
        assertThat(controller.scheduledTaskAuthorize(null).getStatusCodeValue()).isEqualTo(401);
        assertThat(controller.scheduledTaskAuthorize("invalid").getStatusCodeValue()).isEqualTo(401);
        org.springframework.mock.web.MockHttpServletRequest request=new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(AgentAuthorizationInterceptor.CONTEXT_ATTRIBUTE,Collections.singletonMap("userId","scheduled"));
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            assertThat(controller.scheduledTaskAuthorize(null).getBody().getData()).isEqualTo(Collections.singletonMap("userId", "scheduled"));
            controller.messages(input);
            verify(conversations).conversationMessages("{\"userId\":\"scheduled\"}");
        } finally { org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); }
    }

}
