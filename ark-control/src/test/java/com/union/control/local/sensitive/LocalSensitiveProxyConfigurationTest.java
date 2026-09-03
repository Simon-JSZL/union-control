package com.union.control.local.sensitive;

import com.epcc.commons.securityproxy.api.SymmetricalSecurityService;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

public class LocalSensitiveProxyConfigurationTest {

    @Test
    public void defaultProfileProvidesTheLocalSensitiveProxy() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(LocalSensitiveProxyConfiguration.class);
        context.refresh();
        try {
            assertThat(context.getBean("sensitiveProxy"))
                    .isInstanceOf(SymmetricalSecurityService.class);
        } finally {
            context.close();
        }
    }
}
