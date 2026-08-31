package com.union.control.local.sensitive;

import com.epcc.commons.securityproxy.api.SymmetricalSecurityService;
import com.nucc.channel.ark.common.redis.RedisCacheService;
import com.union.control.utils.security.SymmetricalSecurityUtils;
import com.union.control.mapper.interceptor.AESInterceptor;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Bridges this mock application's narrow component scan to production-compatible components. */
@Configuration
public class LocalSensitiveHostConfiguration {
    @Bean
    public RedisCacheService redisCacheService() {
        return new LocalRedisCacheService();
    }

    @Bean
    public SymmetricalSecurityUtils symmetricalSecurityUtils(
            SymmetricalSecurityService sensitiveProxy) {
        return new SymmetricalSecurityUtils(sensitiveProxy);
    }

    @Bean
    public AESInterceptor aesInterceptor() {
        return new AESInterceptor();
    }

    @Bean
    public ConfigurationCustomizer sensitiveInterceptorRegistration(
            AESInterceptor interceptor) {
        return configuration -> configuration.addInterceptor(interceptor);
    }
}
