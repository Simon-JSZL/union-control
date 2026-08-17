package com.union.control.security;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.util.ThreadContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

public class ScheduledExecutionFilterTest {
    @Before
    public void setUp() {
        SecurityUtils.setSecurityManager(new DefaultSecurityManager());
    }

    @After
    public void tearDown() {
        ThreadContext.remove();
    }

    @Test
    public void rejectsMalformedScheduledCredentialWithoutFallingBackToCas() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agent/queryBigData");
        request.addHeader("Authorization", "Scheduled invalid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(new ScheduledExecutionFilter().preHandle(request, response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    public void rejectsMixedCookieAndScheduledAuthentication() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agent/queryBigData");
        request.addHeader("Authorization",
                "Scheduled AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        request.addHeader("Cookie", "CASSESSIONID=session-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(new ScheduledExecutionFilter().preHandle(request, response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
    }
}
