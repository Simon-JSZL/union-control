package com.union.control.local.sensitive;

import com.epcc.commons.securityproxy.api.SymmetricalSecurityService;
import com.nucc.channel.ark.common.redis.RedisCacheService;
import com.union.control.utils.security.SymmetricalSecurityUtils;
import com.union.control.mapper.interceptor.AESInterceptor;
import com.union.control.mapper.interceptor.AddressBookHandler;
import com.union.control.mapper.interceptor.SensitiveResultProcessor;
import com.union.control.service.sensitive.SensitiveFieldCodec;
import com.union.control.service.sensitive.SensitiveRevealPolicy;
import com.union.control.config.SensitiveRevealConfiguration;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Bridges this mock application's narrow component scan to production-compatible components. */
@Configuration
@Import(SensitiveRevealConfiguration.class)
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
    public AESInterceptor aesInterceptor(SensitiveFieldCodec fieldCodec,
            SensitiveRevealPolicy policy, SensitiveResultProcessor resultProcessor,
            AddressBookHandler addressBookHandler) {
        return new AESInterceptor(fieldCodec, policy, resultProcessor, addressBookHandler);
    }

    @Bean
    public ConfigurationCustomizer sensitiveInterceptorRegistration(
            final AESInterceptor interceptor) {
        return configuration -> configuration.addInterceptor(interceptor);
    }
}
