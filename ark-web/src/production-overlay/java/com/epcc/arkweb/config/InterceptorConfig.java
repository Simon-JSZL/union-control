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
