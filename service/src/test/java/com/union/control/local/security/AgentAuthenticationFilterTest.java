package com.union.control.local.security;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.util.ThreadContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

public class AgentAuthenticationFilterTest {
    @Before
    public void setUp() {
        SecurityUtils.setSecurityManager(new DefaultSecurityManager(new LocalCasRealm()));
    }

    @After
    public void tearDown() {
        ThreadContext.unbindSubject();
        ThreadContext.unbindSecurityManager();
    }

    @Test
    public void authenticatesProductionLlmAliasWithCasCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/union-op/llm/scheduledTaskList");
        request.addHeader("Cookie", "CASSESSIONID=session-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = new AgentAuthenticationFilter().preHandle(request, response);

        assertThat(allowed).isTrue();
        assertThat(SecurityUtils.getSubject().isAuthenticated()).isTrue();
    }
}
