package com.epcc.arkweb.mock;

import com.google.code.kaptcha.Producer;
import com.google.code.kaptcha.impl.DefaultKaptcha;
import com.google.code.kaptcha.util.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/** Local wiring only. Production supplies its existing KaptchaConfig / Producer bean. */
@Configuration
public class LocalKaptchaConfiguration {
    @Bean
    public Producer captchaProducer() {
        Properties properties = new Properties();
        properties.setProperty("kaptcha.image.width", "110");
        properties.setProperty("kaptcha.image.height", "40");
        properties.setProperty("kaptcha.textproducer.char.length", "4");
        properties.setProperty("kaptcha.textproducer.font.size", "30");
        properties.setProperty("kaptcha.textproducer.font.names", "SansSerif");
        DefaultKaptcha producer = new DefaultKaptcha();
        producer.setConfig(new Config(properties));
        return producer;
    }
}
