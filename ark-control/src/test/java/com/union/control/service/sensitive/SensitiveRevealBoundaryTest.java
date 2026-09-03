package com.union.control.service.sensitive;

import com.epcc.commons.securityproxy.api.SecurityResult;
import com.epcc.commons.securityproxy.api.SymmetricalSecurityService;
import com.epcc.dubbo.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nucc.channel.ark.common.exception.BaseDataErrorCode;
import com.nucc.channel.ark.common.exception.CheckException;
import com.nucc.channel.ark.common.redis.RedisCacheService;
import com.union.control.mapper.interceptor.AESInterceptor;
import com.union.control.utils.security.SymmetricalSecurityUtils;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SensitiveRevealBoundaryTest {
    @Test
    public void springSelectsTheSensitiveRevealProcessorProductionConstructor() {
        java.lang.reflect.Constructor<?>[] constructors =
                new AutowiredAnnotationBeanPostProcessor().determineCandidateConstructors(
                        SensitiveRevealProcessor.class, "sensitiveRevealProcessor");

        assertNotNull(constructors);
        assertEquals(1, constructors.length);
        assertEquals(2, constructors[0].getParameterTypes().length);
    }

    @Test
    public void sensitiveInterceptorDependenciesAreRequired() throws Exception {
        assertTrue(AESInterceptor.class.getDeclaredField("processor")
                .getAnnotation(Autowired.class).required());
        assertTrue(AESInterceptor.class.getDeclaredField("addressBook")
                .getAnnotation(Autowired.class).required());
    }

    @Test
    public void redisKeyContainsOnlyTheTokenHash() {
        String token = "rt_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        String key = RedisRevealTokenStore.key(token);
        assertTrue(key.matches("sensitive:reveal:[0-9a-f]{64}"));
        assertFalse(key.contains(token));
    }

    @Test
    public void revealReturnsOnlyTheEncryptedFragment() throws Exception {
        SymmetricalSecurityUtils crypto = new SymmetricalSecurityUtils(new PrefixGateway());
        final String ciphertext = crypto.encryptWithCheck("13800138000");
        RedisRevealTokenStore store = new RedisRevealTokenStore(new RedisCacheService() {
            @Override
            public String get(String key) {
                return ciphertext;
            }
        }, 300);
        String token = "rt_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

        assertEquals("13800138000", new SensitiveRevealServiceImpl(store, crypto, new ObjectMapper())
                .reveal("{\"userId\":\"ignored-by-first-version\",\"token\":\"" + token + "\"}"));
    }

    @Test(expected = SensitiveRevealService.InvalidTokenException.class)
    public void malformedTokenNeverReachesTheStore() {
        RedisRevealTokenStore store = new RedisRevealTokenStore(new RedisCacheService() {
            @Override
            public String get(String key) {
                throw new AssertionError("store must not be called");
            }
        }, 300);
        new SensitiveRevealServiceImpl(store,
                new SymmetricalSecurityUtils(new PrefixGateway()), new ObjectMapper())
                .reveal("{\"userId\":\"user-1\",\"token\":\"bad\"}");
    }

    static class PrefixGateway implements SymmetricalSecurityService {
        @Override
        public Result<SecurityResult> encryptByFixedKey(Algorithm algorithm, byte[] plaintext) {
            byte[] prefix = "enc:".getBytes(StandardCharsets.UTF_8);
            byte[] result = new byte[prefix.length + plaintext.length];
            System.arraycopy(prefix, 0, result, 0, prefix.length);
            System.arraycopy(plaintext, 0, result, prefix.length, plaintext.length);
            return Result.success(new SecurityResult(result));
        }

        @Override
        public Result<SecurityResult> decryptByFixedKey(Algorithm algorithm, byte[] ciphertext) {
            if (ciphertext.length < 4) {
                return Result.failure(BaseDataErrorCode.SYSTEM_INNER_ERROR.getCode(), "invalid");
            }
            return Result.success(new SecurityResult(
                    java.util.Arrays.copyOfRange(ciphertext, 4, ciphertext.length)));
        }
    }
}
