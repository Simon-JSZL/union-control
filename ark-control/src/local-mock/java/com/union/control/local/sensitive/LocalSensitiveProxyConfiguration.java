package com.union.control.local.sensitive;

import com.epcc.commons.securityproxy.api.SymmetricalSecurityService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Explicit local-only replacement for the unavailable production sensitiveProxy bean. */
@Configuration
@Profile({"default", "local-sensitive-mock"})
public class LocalSensitiveProxyConfiguration {
    @Bean(name = "sensitiveProxy")
    public SymmetricalSecurityService localSensitiveProxy() {
        return new LocalMockSensitiveProxy();
    }
}
