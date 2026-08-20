package com.union.control.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nucc.channel.ark.common.redis.RedisCacheService;
import com.union.control.mapper.interceptor.AddressBookHandler;
import com.union.control.mapper.interceptor.SensitiveResultProcessor;
import com.union.control.service.sensitive.AddressBookPlaintextPolicy;
import com.union.control.service.sensitive.SensitiveFieldCodec;
import com.union.control.utils.security.SymmetricalSecurityUtils;
import com.union.control.service.sensitive.RedisRevealTokenStore;
import com.union.control.service.sensitive.SensitiveRevealPolicy;
import com.union.control.service.sensitive.SensitiveRevealProcessor;
import com.union.control.service.sensitive.SensitiveRevealService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** New production wiring; only the local sensitiveProxy replacement lives outside this package. */
@Configuration
public class SensitiveRevealConfiguration {
    @Bean
    public RedisRevealTokenStore redisRevealTokenStore(RedisCacheService redis,
            @Value("${sensitive.reveal.ttl-seconds:300}") long ttlSeconds) {
        return new RedisRevealTokenStore(redis, ttlSeconds);
    }

    @Bean
    public SensitiveRevealPolicy sensitiveRevealPolicy(
            @Value("${sensitive.reveal.enabled:false}") boolean enabled) {
        return new SensitiveRevealPolicy(enabled);
    }

    @Bean
    public SensitiveRevealProcessor sensitiveRevealProcessor(
            SymmetricalSecurityUtils crypto, RedisRevealTokenStore tokens) {
        return new SensitiveRevealProcessor(crypto, tokens);
    }

    @Bean
    public SensitiveFieldCodec sensitiveFieldCodec(SymmetricalSecurityUtils crypto) {
        return new SensitiveFieldCodec(crypto);
    }

    @Bean
    public AddressBookPlaintextPolicy addressBookPlaintextPolicy(
            @Value("${sensitive.address-book.plaintext-roles:}") String plaintextRoles) {
        return new AddressBookPlaintextPolicy(plaintextRoles);
    }

    @Bean
    public AddressBookHandler addressBookHandler(SensitiveFieldCodec fieldCodec,
            SensitiveRevealProcessor revealProcessor,
            AddressBookPlaintextPolicy plaintextPolicy) {
        return new AddressBookHandler(fieldCodec, revealProcessor, plaintextPolicy);
    }

    @Bean
    public SensitiveResultProcessor sensitiveResultProcessor(SensitiveFieldCodec fieldCodec,
            SensitiveRevealProcessor revealProcessor, AddressBookHandler addressBookHandler) {
        return new SensitiveResultProcessor(fieldCodec, revealProcessor, addressBookHandler);
    }

    @Bean
    public SensitiveRevealService sensitiveRevealService(RedisRevealTokenStore tokens,
            SymmetricalSecurityUtils crypto, ObjectMapper json) {
        return new SensitiveRevealService(tokens, crypto, json);
    }

}
