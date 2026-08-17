package com.epcc.arkweb.web;

import com.union.control.local.security.AuthenticatedUser;
import com.union.control.local.security.LocalCasRealm;
import com.union.control.service.AgentExecutionService;
import com.union.control.service.ConversationService;
import com.union.control.service.MemoryStoreService;
import com.union.control.service.RunningAnalysisMockService;
import com.union.control.service.ScheduledTaskService;
import com.epcc.arkweb.web.llm.AgentController;
import org.apache.shiro.authz.UnauthenticatedException;
import org.apache.shiro.authz.UnauthorizedException;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.spring.security.interceptor.AuthorizationAttributeSourceAdvisor;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.aop.framework.ProxyFactory;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ShiroPermissionTest {
    private DefaultSecurityManager manager;
    private AgentController controller;
    private RunningAnalysisMockService runningAnalysis;

    @Before
    public void setUp() {
        manager = new DefaultSecurityManager(new LocalCasRealm());
        runningAnalysis = mock(RunningAnalysisMockService.class);
        when(runningAnalysis.queryBigData(LocalCasRealm.cookieHeader(), Collections.emptyMap()))
                .thenReturn(Collections.emptyMap());
        AuthorizationAttributeSourceAdvisor advisor = new AuthorizationAttributeSourceAdvisor();
        advisor.setSecurityManager(manager);
        ProxyFactory proxy = new ProxyFactory(new AgentController(
                mock(ConversationService.class),
                mock(AgentExecutionService.class),
                mock(MemoryStoreService.class),
                runningAnalysis,
                mock(ScheduledTaskService.class)));
        proxy.addAdvisor(advisor);
        controller = (AgentController) proxy.getProxy();
        ThreadContext.bind(manager);
    }

    @After
    public void tearDown() { ThreadContext.remove(); }

    @Test
    public void assistantManagerPermissionAllowsTheLocalCasUser() {
        bind(new AuthenticatedUser(LocalCasRealm.USER_ID, LocalCasRealm.ORG_CODE,
                LocalCasRealm.ROLE_ID, LocalCasRealm.cookieHeader()));

        controller.queryBigData(LocalCasRealm.cookieHeader(), Collections.emptyMap());

        verify(runningAnalysis).queryBigData(LocalCasRealm.cookieHeader(), Collections.emptyMap());
    }

    @Test
    public void annotationRejectsMissingAuthenticationAndMissingPermission() {
        bind(null);
        assertThatThrownBy(() -> controller.queryBigData(null, Collections.emptyMap()))
                .isInstanceOf(UnauthenticatedException.class);

        bind("authenticated-without-agent-permission");
        assertThatThrownBy(() -> controller.queryBigData(
                LocalCasRealm.cookieHeader(), Collections.emptyMap()))
                .isInstanceOf(UnauthorizedException.class);
    }

    private void bind(Object principal) {
        ThreadContext.unbindSubject();
        Subject.Builder builder = new Subject.Builder(manager);
        if (principal != null) {
            builder.principals(new SimplePrincipalCollection(principal, LocalCasRealm.class.getName()))
                    .authenticated(true);
        }
        ThreadContext.bind(builder.buildSubject());
    }
}
