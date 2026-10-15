package com.epcc.arkweb.config;

import com.epcc.arkweb.filter.CSRFInterceptor;
import com.epcc.arkweb.filter.RefererInterceptor;
import com.epcc.arkweb.filter.SessionIpInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class InterceptorConfig implements WebMvcConfigurer {
    @Autowired
    private RefererInterceptor refererInterceptor;
    @Autowired
    private SessionIpInterceptor sessionIpInterceptor;
    @Autowired
    private CSRFInterceptor csrfInterceptor;

    // 【定时任务鉴权：修改】三个浏览器拦截器均排除 /agent/**，避免无浏览器会话的 Scheduled 请求被拦截。
    // /agent/** 的认证和权限检查由 Shiro 的 agentAuth 与 @RequiresPermissions 承担。
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(refererInterceptor).addPathPatterns("/**")
                .excludePathPatterns("/", "/login.html", "/logout", "/agent/**");
        registry.addInterceptor(sessionIpInterceptor).addPathPatterns("/**")
                .excludePathPatterns("/", "/login.html", "/error",
                        "/healthcheck.html", "/logout", "/agent/**");
        registry.addInterceptor(csrfInterceptor).addPathPatterns("/audit/**")
                .excludePathPatterns("/", "/login.html", "/logout", "/agent/**");
    }
}
