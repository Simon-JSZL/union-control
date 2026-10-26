package com.epcc.arkweb.web.llm;

import com.epcc.arkweb.ShiroTestSupport;
import com.epcc.arkweb.vo.llm.ScheduledTaskCommandVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.service.ScheduledTaskService;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class ScheduledTaskControllerTest {
    @After public void clearUser() { ShiroTestSupport.clear(); }

    @Test
    public void storesExecutionIdentityOnlyWhenSavingATask() throws Exception {
        ShiroTestSupport.bindLocalUser();
        org.apache.shiro.subject.Subject subject = org.mockito.Mockito.spy(org.apache.shiro.SecurityUtils.getSubject());
        org.mockito.Mockito.doNothing().when(subject).checkPermission("/assistantManager/page");
        org.apache.shiro.util.ThreadContext.bind(subject);
        ObjectMapper json = new ObjectMapper();
        ScheduledTaskService service = mock(ScheduledTaskService.class);
        ScheduledTaskController target = new ScheduledTaskController();
        ReflectionTestUtils.setField(target, "scheduledTaskService", service);
        ReflectionTestUtils.setField(target, "json", json);
        org.springframework.aop.framework.ProxyFactory proxy = new org.springframework.aop.framework.ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvisor(new com.epcc.arkweb.config.ShiroConfig().authorizationAttributeSourceAdvisor(null));
        ScheduledTaskController controller = (ScheduledTaskController) proxy.getProxy();
        ScheduledTaskCommandVO command = new ScheduledTaskCommandVO();
        command.setTitle("daily report");
        command.setPrompt("report");
        command.setScheduleType("INTERVAL");
        command.setIntervalSeconds(3600L);
        command.setTimezone("Asia/Shanghai");
        controller.create(command);
        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(service).create(input.capture());
        JsonNode saved = json.readTree(input.getValue());
        assertThat(saved.path("userId").asText()).isEqualTo(ShiroTestSupport.USER_ID);
        assertThat(saved.path("orgCode").asText()).isEqualTo(ShiroTestSupport.ORG_CODE);
        assertThat(saved.path("roleId").asText()).isEqualTo(ShiroTestSupport.ROLE_ID);
        assertThat(saved.path("prompt").asText()).isEqualTo("report");
        assertThat(saved.path("permissions").toString()).isEqualTo("[\"/assistantManager/page\"]");
        org.mockito.Mockito.verify(subject).checkPermission("/assistantManager/page");

        controller.update(command);
        verify(service).update(input.capture());
        assertThat(json.readTree(input.getValue()).path("permissions").toString())
                .isEqualTo("[\"/assistantManager/page\"]");
        controller.detail(7L);
        verify(service).detail("{\"taskId\":7,\"userId\":\"user-1\"}");
        org.mockito.Mockito.doThrow(new org.apache.shiro.authz.UnauthorizedException())
                .when(subject).checkPermission("/assistantManager/page");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.create(command))
                .isInstanceOf(org.apache.shiro.authz.UnauthorizedException.class);
        verify(service, org.mockito.Mockito.times(1)).create(org.mockito.Matchers.anyString());

    }

    @Test public void everyRoutePreservesIdentityPagingAndServiceResponses() throws Exception {
        ShiroTestSupport.bindLocalUser();
        ScheduledTaskService service=mock(ScheduledTaskService.class, (org.mockito.stubbing.Answer<Object>) call ->
                call.getMethod().getReturnType() == java.util.Map.class
                        ? java.util.Collections.singletonMap("success",true) : org.mockito.Mockito.RETURNS_DEFAULTS.answer(call));
        ScheduledTaskController controller=new ScheduledTaskController();
        ReflectionTestUtils.setField(controller,"scheduledTaskService",service);
        ReflectionTestUtils.setField(controller,"json",new ObjectMapper());
        ScheduledTaskCommandVO command=new ScheduledTaskCommandVO(); command.setTaskId(7L);
        assertThat(controller.create(command).isSuccess()).isTrue();
        assertThat(controller.update(command).isSuccess()).isTrue();
        com.epcc.arkweb.vo.llm.ScheduledTaskQueryVO query=new com.epcc.arkweb.vo.llm.ScheduledTaskQueryVO();
        query.setTaskId(7L); query.setPage(null); query.setPageSize(null);
        assertThat(controller.list(query).isSuccess()).isTrue();
        assertThat(controller.runList(query).isSuccess()).isTrue();
        query.setPage(2); query.setPageSize(10); controller.list(query); controller.runList(query);
        assertThat(controller.detail(7L).isSuccess()).isTrue();
        assertThat(controller.runDetail(8L).isSuccess()).isTrue();
        assertThat(controller.unread().isSuccess()).isTrue();
        ScheduledTaskController.IdCommand id=new ScheduledTaskController.IdCommand(); id.setTaskId(7L); id.setRunId(8L);
        assertThat(controller.start(id).isSuccess()).isTrue(); assertThat(controller.pause(id).isSuccess()).isTrue();
        assertThat(controller.discard(id).isSuccess()).isTrue(); assertThat(controller.open(id).isSuccess()).isTrue();
        verify(service).list("{\"keyword\":null,\"status\":null,\"page\":1,\"pageSize\":20,\"userId\":\"user-1\"}");
        verify(service).open("{\"runId\":8,\"userId\":\"user-1\"}");
        for (Long invalid:new Long[]{null,0L,-1L}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.detail(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.create(null)).isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.when(service.unread(org.mockito.Matchers.anyString())).thenReturn(null);
        assertThat(controller.unread().getMsg()).isEqualTo("定时任务服务不可用");
        java.util.Map<String,Object> failure=new java.util.LinkedHashMap<>(); failure.put("success",false); failure.put("errorMsg","failed");
        org.mockito.Mockito.when(service.unread(org.mockito.Matchers.anyString())).thenReturn(failure);
        assertThat(controller.unread().getMsg()).isEqualTo("failed");
    }

}
