package com.epcc.arkweb.config;

import com.epcc.arkweb.helper.AuthenticatedRequest;
import com.epcc.arkweb.mock.ArkAuthServiceMock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.After;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.servlet.Filter;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

public class LocalShiroIntegrationTest {
    @After
    public void clearShiro() {
        ThreadContext.remove();
        SecurityUtils.setSecurityManager(null);
    }

    @Test
    public void authenticatedRequestUsesTheShiroSubjectAndPermissions() {
        AnnotationConfigApplicationContext context = context("local-auth-mock");
        try {
            SecurityManager manager = context.getBean(SecurityManager.class);
            SecurityUtils.setSecurityManager(manager);
            Subject subject = new Subject.Builder(manager).buildSubject();
            subject.login(new UsernamePasswordToken("user-1", "local-only"));
            subject.execute(() -> {
                assertThat(context.getBean(AuthenticatedRequest.class).actor().getRoleId())
                        .isEqualTo("role-1");
                assertThat(SecurityUtils.getSubject().isPermitted("/assistantManager/page"))
                        .isTrue();
            });
        } finally {
            context.close();
        }
    }

    @Test
    public void localRequestAuthenticatesWithoutSubmittingTheLoginForm() throws Exception {
        AnnotationConfigApplicationContext context = context("local-auth-mock");
        try {
            SecurityManager manager = context.getBean(SecurityManager.class);
            SecurityUtils.setSecurityManager(manager);
            context.getBean("localAuthFilter", Filter.class).doFilter(
                    new MockHttpServletRequest(), new MockHttpServletResponse(),
                    new MockFilterChain());
            assertThat(SecurityUtils.getSubject().isAuthenticated()).isTrue();
            assertThat(SecurityUtils.getSubject().isPermitted("/assistantManager/page")).isTrue();
        } finally {
            context.close();
        }
    }

    @Test
    public void scheduledRequestDoesNotCreateAShiroLogin() throws Exception {
        AnnotationConfigApplicationContext context = context("local-auth-mock");
        try {
            SecurityManager manager = context.getBean(SecurityManager.class);
            SecurityUtils.setSecurityManager(manager);
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("Authorization",
                    "Scheduled AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
            context.getBean("localAuthFilter", Filter.class).doFilter(
                    request, new MockHttpServletResponse(), new MockFilterChain());
            assertThat(SecurityUtils.getSubject().isAuthenticated()).isFalse();
        } finally {
            context.close();
        }
    }

    @Test
    public void localShiroDoesNotRegisterWithoutTheExplicitProfile() {
        AnnotationConfigApplicationContext context = context();
        try {
            assertThat(context.getBeansOfType(ShiroConfig.class)).isEmpty();
            assertThat(context.getBeansOfType(ArkAuthServiceMock.class)).isEmpty();
        } finally {
            context.close();
        }
    }

    private static AnnotationConfigApplicationContext context(String... profiles) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profiles);
        if (profiles.length > 0)
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "test", Collections.<String, Object>singletonMap("arkshiro.sso", "false")));
        context.register(TestConfiguration.class);
        context.refresh();
        return context;
    }

    @Configuration
    @Import({ShiroConfig.class, ArkAuthServiceMock.class})
    public static class TestConfiguration {
        @Bean public static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }
        @Bean public ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean public AuthenticatedRequest authenticatedRequest(ObjectMapper json) {
            return new AuthenticatedRequest(json);
        }
    }
}
