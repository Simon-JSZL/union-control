package com.epcc.arkweb.config;

import com.epcc.arkweb.filter.AgentAuthenticationFilter;
import org.apache.shiro.mgt.DefaultSessionStorageEvaluator;
import org.apache.shiro.mgt.DefaultSubjectDAO;
import org.apache.shiro.realm.Realm;
import com.union.control.mapper.ScheduledTaskMapper;
import org.apache.shiro.spring.LifecycleBeanPostProcessor;
import org.apache.shiro.spring.security.interceptor.AuthorizationAttributeSourceAdvisor;
import org.apache.shiro.spring.web.ShiroFilterFactoryBean;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;

import javax.servlet.Filter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
public class ShiroConfig {
    @Bean
    public Realm localCasRealm() { return new LocalCasRealm(); }

    @Bean
    public Realm scheduledExecutionRealm(ScheduledTaskMapper mapper) {
        return new ScheduledExecutionRealm(mapper);
    }

    @Bean
    public DefaultWebSecurityManager securityManager(
            @Qualifier("localCasRealm") Realm localCasRealm,
            @Qualifier("scheduledExecutionRealm") Realm scheduledExecutionRealm) {
        DefaultWebSecurityManager manager = new DefaultWebSecurityManager(
                Arrays.asList(localCasRealm, scheduledExecutionRealm));
        DefaultSessionStorageEvaluator sessions = new DefaultSessionStorageEvaluator();
        sessions.setSessionStorageEnabled(false);
        DefaultSubjectDAO subjectDAO = new DefaultSubjectDAO();
        subjectDAO.setSessionStorageEvaluator(sessions);
        manager.setSubjectDAO(subjectDAO);
        return manager;
    }

    @Bean
    public ShiroFilterFactoryBean shiroFilter(DefaultWebSecurityManager securityManager) {
        ShiroFilterFactoryBean bean = new ShiroFilterFactoryBean();
        bean.setSecurityManager(securityManager);
        Map<String, Filter> filters = new LinkedHashMap<>();
        filters.put("agentAuth", new AgentAuthenticationFilter());
        bean.setFilters(filters);
        bean.setFilterChainDefinitionMap(
                java.util.Collections.singletonMap("/**", "noSessionCreation,agentAuth"));
        return bean;
    }

    @Bean
    public static LifecycleBeanPostProcessor lifecycleBeanPostProcessor() {
        return new LifecycleBeanPostProcessor();
    }

    @Bean
    public static DefaultAdvisorAutoProxyCreator advisorAutoProxyCreator() {
        DefaultAdvisorAutoProxyCreator creator = new DefaultAdvisorAutoProxyCreator();
        creator.setProxyTargetClass(true);
        return creator;
    }

    @Bean
    public AuthorizationAttributeSourceAdvisor authorizationAdvisor(
            DefaultWebSecurityManager securityManager) {
        AuthorizationAttributeSourceAdvisor advisor = new AuthorizationAttributeSourceAdvisor();
        advisor.setSecurityManager(securityManager);
        return advisor;
    }
}
