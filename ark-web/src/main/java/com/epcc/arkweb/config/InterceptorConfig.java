package com.epcc.arkweb.config;

import com.epcc.arkweb.web.llm.AgentAuthorizationInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;

/** Standalone equivalent of the production MVC registration. */
@Configuration
public class InterceptorConfig extends WebMvcConfigurerAdapter {
    @Autowired
    private AgentAuthorizationInterceptor agentAuthorizationInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(agentAuthorizationInterceptor).addPathPatterns("/agent/**");
    }
}
