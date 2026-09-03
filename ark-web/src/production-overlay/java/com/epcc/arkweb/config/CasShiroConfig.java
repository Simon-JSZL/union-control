package com.epcc.arkweb.config;

import com.epcc.arkweb.service.redis.ShiroSessionRedisDao;
import com.epcc.arkweb.service.shirosession.ShiroSessionListener;
import com.epcc.baseservice.dal.service.UnifyRedisService;
import org.apache.shiro.authc.pam.FirstSuccessfulStrategy;
import org.apache.shiro.authc.pam.ModularRealmAuthenticator;
import org.apache.shiro.cas.CasFilter;
import org.apache.shiro.cas.CasRealm;
import org.apache.shiro.cas.CasSubjectFactory;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.mgt.SessionsSecurityManager;
import org.apache.shiro.session.SessionListener;
import org.apache.shiro.spring.LifecycleBeanPostProcessor;
import org.apache.shiro.spring.security.interceptor.AuthorizationAttributeSourceAdvisor;
import org.apache.shiro.spring.web.ShiroFilterFactoryBean;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.apache.shiro.web.servlet.SimpleCookie;
import org.apache.shiro.web.session.mgt.DefaultWebSessionManager;
import org.jasig.cas.client.session.SingleSignOutFilter;
import org.jasig.cas.client.session.SingleSignOutHttpSessionListener;
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.DelegatingFilterProxy;

import javax.servlet.Filter;
import java.util.*;

/***
 * @auther: suwen
 * @date: 17:58 2021/2/26
 */
@Configuration
@ConfigurationProperties(prefix = "cas")
@ConditionalOnProperty(prefix = "arkshiro", name = "sso",havingValue = "true")
public class CasShiroConfig {

    @Value("${cas.casServerUrlPrefix}")
    public String casServerUrlPrefix;

    @Value("${cookie.secure:false}")
    private String cookieSecure;

    @Value("${cas.casLoginUrl}")
    public String casLoginUrl;

    @Value("${cas.casLogoutUrl}")
    public String casLogoutUrl;

    @Value("${cas.shiroServerUrlPrefix}")
    public String shiroServerUrlPrefix;

    @Value("${cas.casFilterUrlPattern}")
    public String casFilterUrlPattern;

    @Value("${cas.loginUrl}")
    public String loginUrl;

    @Value("${cas.logoutUrl}")
    public String logoutUrl;

    public static final String loginSuccessUrl = "/index.html";

    public static final String unauthorizedUrl = "/403.html";

    /*@Bean
    public SessionsSecurityManager securityManager(CasRealm realm){
        DefaultWebSecurityManager defaultWebSecurityManager = new DefaultWebSecurityManager();
        defaultWebSecurityManager.setRealm(realm);
        defaultWebSecurityManager.setSubjectFactory(new CasSubjectFactory());
        return defaultWebSecurityManager;
    }*/

    @Bean
    public ArkCasRealm arkCasRealm(){
        ArkCasRealm arkCasRealm = new ArkCasRealm();
        arkCasRealm.setCasServerUrlPrefix(casServerUrlPrefix);
        arkCasRealm.setCasService(shiroServerUrlPrefix+casFilterUrlPattern);
        arkCasRealm.setAuthenticationCachingEnabled(false);
        return arkCasRealm;
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public ServletListenerRegistrationBean singleSingOutHttpSessionListener(){
        ServletListenerRegistrationBean bean = new ServletListenerRegistrationBean();
        bean.setListener(new SingleSignOutHttpSessionListener());
        bean.setEnabled(true);
        return bean;
    }

    @Bean
    public FilterRegistrationBean singleSingOutFilter(){
        FilterRegistrationBean bean = new FilterRegistrationBean();
        bean.setName("singleSingOutFilter");
        bean.setFilter(new SingleSignOutFilter());
        bean.addUrlPatterns("/*");
        bean.setEnabled(true);
        return bean;
    }

    @Bean
    public FilterRegistrationBean delegatingFilterProxy(){
        FilterRegistrationBean filterRegistrationBean = new FilterRegistrationBean();
        filterRegistrationBean.setFilter(new DelegatingFilterProxy("shiroFilter"));
        filterRegistrationBean.addInitParameter("targetFilterLifecycle","true");
        filterRegistrationBean.setEnabled(true);
        filterRegistrationBean.addUrlPatterns("/*");
        return filterRegistrationBean;
    }

    @Bean(name="casFilter")
    public CasFilter getCasFilter(){
        CasFilter casFilter = new CasFilter();
        casFilter.setName("casFilter");
        casFilter.setEnabled(true);
        casFilter.setFailureUrl(loginUrl);
        casFilter.setSuccessUrl(loginSuccessUrl);
        return casFilter;
    }

    @Bean(name = "shiroFilter")
    public ShiroFilterFactoryBean shiroFilter(SecurityManager securityManager, CasFilter casFilter){
        ShiroFilterFactoryBean shiroFilterFactoryBean = new ShiroFilterFactoryBean();
        shiroFilterFactoryBean.setSecurityManager(securityManager);
        shiroFilterFactoryBean.setLoginUrl(loginUrl);
        shiroFilterFactoryBean.setUnauthorizedUrl(unauthorizedUrl);

        Map<String,Filter> filters = new HashMap<>();
        filters.put("casFilter",casFilter);
        shiroFilterFactoryBean.setFilters(filters);
        //拦截器.
        Map<String, String> filterMap = new LinkedHashMap<>();
        // 配置不会被拦截的链接 顺序判断
        filterMap.put(casFilterUrlPattern,"casFilter");
        filterMap.put("/css/**", "anon");
        filterMap.put("/images/**", "anon");
        filterMap.put("/script/**", "anon");
        filterMap.put("/plugins/**", "anon");
        filterMap.put("/api/**", "anon");
        filterMap.put("/agent/**", "anon");
        filterMap.put("/lhyw/kickidc", "anon");
        filterMap.put("/lhyw/recoverIdc", "anon");
        filterMap.put("/lhyw/kickidc/healthcheck", "anon");
        filterMap.put("/healthcheck.html", "anon");
        filterMap.put("/SSL/healthcheck.html", "anon");
        filterMap.put("/**","authc");

        shiroFilterFactoryBean.setFilterChainDefinitionMap(filterMap);
        return shiroFilterFactoryBean;
    }

    @Bean
    public static LifecycleBeanPostProcessor lifecycleBeanPostProcessor(){
        return new LifecycleBeanPostProcessor();
    }

    @Bean
    @DependsOn({"lifecycleBeanPostProcessor"})
    public DefaultAdvisorAutoProxyCreator advisorAutoProxyCreator(){
        DefaultAdvisorAutoProxyCreator advisorAutoProxyCreator = new DefaultAdvisorAutoProxyCreator();
        advisorAutoProxyCreator.setProxyTargetClass(true);
        return advisorAutoProxyCreator;
    }

    @Bean
    public AuthorizationAttributeSourceAdvisor authorizationAttributeSourceAdvisor(SecurityManager securityManager){
        AuthorizationAttributeSourceAdvisor authorizationAttributeSourceAdvisor = new AuthorizationAttributeSourceAdvisor();
        authorizationAttributeSourceAdvisor.setSecurityManager(securityManager);
        return authorizationAttributeSourceAdvisor;
    }

    @Bean
    public ModularRealmAuthenticator modularRealmAuthenticator(){
        ModularRealmAuthenticator modularRealmAuthenticator = new ModularRealmAuthenticator();
        modularRealmAuthenticator.setAuthenticationStrategy(new FirstSuccessfulStrategy());
        return  modularRealmAuthenticator;
    }
    @Bean
    public DefaultWebSessionManager defaultWebSessionManager(ShiroSessionRedisDao shiroSessionRedisDao){
        DefaultWebSessionManager defaultWebSessionManager = new DefaultWebSessionManager();
        //全局会话超时时间，默认30分钟(1800000)
        defaultWebSessionManager.setGlobalSessionTimeout(1800000);
        //是否在会话过期后会调用SessionDAO的delete方法删除会话 默认true
        defaultWebSessionManager.setDeleteInvalidSessions(true);
        //是否开启会话验证器任务 默认true
        defaultWebSessionManager.setSessionValidationSchedulerEnabled(true);
        //会话验证器调度时间
        defaultWebSessionManager.setSessionValidationInterval(900000);
        defaultWebSessionManager.setSessionDAO(shiroSessionRedisDao);
        //默认JSESSIONID，同tomcat/jetty在cookie中缓存标识相同，修改用于防止访问404页面时，容器生成的标识把shiro的覆盖掉
        SimpleCookie simpleCookie=new SimpleCookie("CASSESSIONID");
        simpleCookie.setHttpOnly(true);
        simpleCookie.setSecure(Boolean.parseBoolean(cookieSecure));
        defaultWebSessionManager.setSessionIdCookie(new SimpleCookie(simpleCookie));
        //在地址栏去掉JSESSIONID
        defaultWebSessionManager.setSessionIdUrlRewritingEnabled(false);
        return defaultWebSessionManager;
    }

    @Bean
    public DefaultWebSecurityManager defaultWebSecurityManager(DefaultWebSessionManager defaultWebSessionManager, ModularRealmAuthenticator modularRealmAuthenticator, ArkCasRealm arkCasRealm){
        DefaultWebSecurityManager webSecurityManager = new DefaultWebSecurityManager();
        webSecurityManager.setSubjectFactory(new CasSubjectFactory());
        webSecurityManager.setAuthenticator(modularRealmAuthenticator);
        webSecurityManager.setRealm(arkCasRealm);
        webSecurityManager.setSessionManager(defaultWebSessionManager);
        return webSecurityManager;
    }


    @Bean
    public ShiroSessionRedisDao shiroSessionRedisDao(UnifyRedisService unifyRedisService){
        ShiroSessionRedisDao shiroSessionRedisDao = new ShiroSessionRedisDao();
        shiroSessionRedisDao.setRedisCache(unifyRedisService);
        return shiroSessionRedisDao;
    }


}
