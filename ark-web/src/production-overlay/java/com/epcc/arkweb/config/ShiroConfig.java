package com.epcc.arkweb.config;

import com.epcc.arkweb.filter.LoginFormFilter;
import com.epcc.arkweb.service.redis.ShiroSessionRedisDao;
import com.epcc.arkweb.service.shirosession.ShiroSessionListener;
import com.epcc.baseservice.dal.service.UnifyRedisService;
import org.apache.shiro.authc.pam.FirstSuccessfulStrategy;
import org.apache.shiro.authc.pam.ModularRealmAuthenticator;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.session.SessionListener;
import org.apache.shiro.spring.LifecycleBeanPostProcessor;
import org.apache.shiro.spring.security.interceptor.AuthorizationAttributeSourceAdvisor;
import org.apache.shiro.spring.web.ShiroFilterFactoryBean;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.apache.shiro.web.servlet.SimpleCookie;
import org.apache.shiro.web.session.mgt.DefaultWebSessionManager;
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import javax.servlet.Filter;
import java.util.*;

/**
 * shiro配置
 */
@Configuration
@ConditionalOnProperty(prefix = "arkshiro", name = "sso",havingValue = "false")
public class ShiroConfig {
    @Value("${prop.flag}")
    private String propFlag;

    @Value("${cookie.secure:false}")
    private String cookieSecure;

    @Bean
    public ShiroFilterFactoryBean shiroFilterFactoryBean(SecurityManager securityManager) {
        ShiroFilterFactoryBean shiroFilterFactoryBean = new ShiroFilterFactoryBean();
        shiroFilterFactoryBean.setSecurityManager(securityManager);
        //拦截器.
        Map<String, String> filterMap = new LinkedHashMap<>();
        // 配置不会被拦截的链接 顺序判断
        /*filterMap.put("/arkvue/**", "anon");*/
        filterMap.put("/error", "anon");
        filterMap.put("/css/**", "anon");
        filterMap.put("/images/**", "anon");
        filterMap.put("/script/**", "anon");
        filterMap.put("/static/**", "anon");
        filterMap.put("/plugins/**", "anon");
        filterMap.put("/.ico", "anon");
        filterMap.put("/healthcheck.html", "anon");
        filterMap.put("/SSL/healthcheck.html", "anon");
        filterMap.put("/agent/**", "anon");
        //配置退出 过滤器,其中的具体的退出代码Shiro已经替我们实现了
//        filterMap.put("/loginOut.do", "logout");

        //<!-- 过滤链定义，从上向下顺序执行，一般将/**放在最为下边 -->:这是一个坑呢，一不小心代码就不好使了;
        //<!-- authc:所有url都必须认证通过才可以访问; anon:所有url都都可以匿名访问-->
        filterMap.put("/**", "authc");
        filterMap.put("/union-op/**", "authc");
        // 如果不设置默认会自动寻找Web工程根目录下的"/login.jsp"页面
        shiroFilterFactoryBean.setLoginUrl("/login.html");
        //未授权界面;
        shiroFilterFactoryBean.setUnauthorizedUrl("/403.html");
        shiroFilterFactoryBean.setFilterChainDefinitionMap(filterMap);
        // 登录成功后要跳转的链接
        shiroFilterFactoryBean.setSuccessUrl("/index.html");
        Map<String, Filter> filters = new HashMap<>();
        LoginFormFilter loginFormFilter = new LoginFormFilter(propFlag);
        filters.put("authc",loginFormFilter);
        shiroFilterFactoryBean.setFilters(filters);


        return shiroFilterFactoryBean;
    }

    @Bean
    public static DefaultAdvisorAutoProxyCreator getDefaultAdvisorAutoProxyCreator(){
        DefaultAdvisorAutoProxyCreator defaultAdvisorAutoProxyCreator = new DefaultAdvisorAutoProxyCreator();
        defaultAdvisorAutoProxyCreator.setUsePrefix(true);
        return defaultAdvisorAutoProxyCreator;
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
        SimpleCookie simpleCookie=new SimpleCookie("SHRIOSESSIONID");
        simpleCookie.setHttpOnly(true);
        simpleCookie.setSecure(Boolean.parseBoolean(cookieSecure));

        defaultWebSessionManager.setSessionIdCookie(new SimpleCookie(simpleCookie));
        Collection<SessionListener> listeners = new ArrayList<>();
        listeners.add(new ShiroSessionListener());
        defaultWebSessionManager.setSessionListeners(listeners);
        //在地址栏去掉JSESSIONID
        defaultWebSessionManager.setSessionIdUrlRewritingEnabled(false);
        return defaultWebSessionManager;
    }

    @Bean
    public DefaultWebSecurityManager defaultWebSecurityManager(DefaultWebSessionManager defaultWebSessionManager,ModularRealmAuthenticator modularRealmAuthenticator,Realm realm){
        DefaultWebSecurityManager webSecurityManager = new DefaultWebSecurityManager();
        webSecurityManager.setAuthenticator(modularRealmAuthenticator);
        webSecurityManager.setRealm(realm);
        webSecurityManager.setSessionManager(defaultWebSessionManager);
        return webSecurityManager;
    }

    @Bean
    public Realm arkRealm() {
        Realm arkRealm = new Realm();
        return arkRealm;
    }

    @Bean
    public ShiroSessionRedisDao shiroSessionRedisDao(UnifyRedisService unifyRedisService){
        ShiroSessionRedisDao shiroSessionRedisDao = new ShiroSessionRedisDao();
        shiroSessionRedisDao.setRedisCache(unifyRedisService);
        return shiroSessionRedisDao;
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

}
