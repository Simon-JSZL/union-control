package com.epcc.arkweb.config;

import com.epcc.arkweb.filter.LoginFormFilter;
import org.apache.shiro.authc.pam.FirstSuccessfulStrategy;
import org.apache.shiro.authc.pam.ModularRealmAuthenticator;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.spring.LifecycleBeanPostProcessor;
import org.apache.shiro.spring.security.interceptor.AuthorizationAttributeSourceAdvisor;
import org.apache.shiro.spring.web.ShiroFilterFactoryBean;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.apache.shiro.web.servlet.SimpleCookie;
import org.apache.shiro.web.session.mgt.DefaultWebSessionManager;
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import javax.servlet.Filter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Standalone wiring with the same class, bean and filter names as production. */
@Configuration
@ConditionalOnProperty(prefix = "arkshiro", name = "sso", havingValue = "false")
public class ShiroConfig {
    @Autowired(required = false)
    @Qualifier("localAuthFilter")
    private Filter localAuthFilter;

    @Bean
    public ShiroFilterFactoryBean shiroFilterFactoryBean(SecurityManager securityManager) {
        ShiroFilterFactoryBean factory = new ShiroFilterFactoryBean();
        factory.setSecurityManager(securityManager);
        factory.setLoginUrl("/login.html");
        factory.setUnauthorizedUrl("/403.html");
        factory.setSuccessUrl("/index.html");

        Map<String, String> filterMap = new LinkedHashMap<>();
        filterMap.put("/error", "anon");
        filterMap.put("/css/**", "anon");
        filterMap.put("/images/**", "anon");
        filterMap.put("/script/**", "anon");
        filterMap.put("/static/**", "anon");
        filterMap.put("/plugins/**", "anon");
        filterMap.put("/.ico", "anon");
        filterMap.put("/healthcheck.html", "anon");
        filterMap.put("/SSL/healthcheck.html", "anon");
        filterMap.put("/agent/**", localAuthFilter == null ? "anon" : "localAuth,anon");
        filterMap.put("/**", localAuthFilter == null ? "authc" : "localAuth,authc");
        filterMap.put("/union-op/**", "authc");
        factory.setFilterChainDefinitionMap(filterMap);

        Map<String, Filter> filters = new HashMap<>();
        filters.put("authc", new LoginFormFilter("LOCAL"));
        if (localAuthFilter != null) filters.put("localAuth", localAuthFilter);
        factory.setFilters(filters);
        return factory;
    }

    @Bean
    public static DefaultAdvisorAutoProxyCreator getDefaultAdvisorAutoProxyCreator() {
        DefaultAdvisorAutoProxyCreator creator = new DefaultAdvisorAutoProxyCreator();
        creator.setUsePrefix(true);
        return creator;
    }

    @Bean
    public ModularRealmAuthenticator modularRealmAuthenticator() {
        ModularRealmAuthenticator authenticator = new ModularRealmAuthenticator();
        authenticator.setAuthenticationStrategy(new FirstSuccessfulStrategy());
        return authenticator;
    }

    @Bean
    public DefaultWebSessionManager defaultWebSessionManager() {
        DefaultWebSessionManager sessions = new DefaultWebSessionManager();
        sessions.setGlobalSessionTimeout(1800000);
        sessions.setDeleteInvalidSessions(true);
        sessions.setSessionValidationSchedulerEnabled(true);
        sessions.setSessionValidationInterval(900000);
        SimpleCookie cookie = new SimpleCookie("SHRIOSESSIONID");
        cookie.setHttpOnly(true);
        sessions.setSessionIdCookie(new SimpleCookie(cookie));
        sessions.setSessionIdUrlRewritingEnabled(false);
        return sessions;
    }

    @Bean
    public DefaultWebSecurityManager defaultWebSecurityManager(
            DefaultWebSessionManager sessions,
            ModularRealmAuthenticator authenticator,
            Realm realm) {
        DefaultWebSecurityManager manager = new DefaultWebSecurityManager();
        manager.setAuthenticator(authenticator);
        manager.setRealm(realm);
        manager.setSessionManager(sessions);
        return manager;
    }

    @Bean
    public Realm arkRealm() {
        return new Realm();
    }

    @Bean
    public static LifecycleBeanPostProcessor lifecycleBeanPostProcessor() {
        return new LifecycleBeanPostProcessor();
    }

    @Bean
    @DependsOn("lifecycleBeanPostProcessor")
    public DefaultAdvisorAutoProxyCreator advisorAutoProxyCreator() {
        DefaultAdvisorAutoProxyCreator creator = new DefaultAdvisorAutoProxyCreator();
        creator.setProxyTargetClass(true);
        return creator;
    }

    @Bean
    public AuthorizationAttributeSourceAdvisor authorizationAttributeSourceAdvisor(
            SecurityManager securityManager) {
        AuthorizationAttributeSourceAdvisor advisor = new AuthorizationAttributeSourceAdvisor();
        advisor.setSecurityManager(securityManager);
        return advisor;
    }
}
