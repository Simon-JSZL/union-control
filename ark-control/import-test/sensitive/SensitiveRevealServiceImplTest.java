package com.union.control.service.sensitive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nucc.channel.ark.common.exception.CheckException;
import com.nucc.channel.ark.common.redis.RedisCacheService;
import com.nucc.channel.ark.common.util.ResultUtil;
import com.union.control.utils.security.SymmetricalSecurityUtils;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class SensitiveRevealServiceImplTest {
    private static final String TOKEN = "rt_" + String.join("", Collections.nCopies(43, "a"));
    private final RedisCacheService redis = mock(RedisCacheService.class);
    private final SymmetricalSecurityUtils crypto = mock(SymmetricalSecurityUtils.class);
    private final SensitiveRevealServiceImpl service = new SensitiveRevealServiceImpl(
            new RedisRevealTokenStore(redis, 1800), crypto, new ObjectMapper());

    @Test
    public void validTokenReturnsPlaintextForValidAndInvalidRequestIds() throws Exception {
        when(redis.get(RedisRevealTokenStore.key(TOKEN))).thenReturn("cipher");
        when(crypto.decryptWithCheckNoLog("cipher")).thenReturn("alice@example.com");
        for (Object requestId : Arrays.asList("a", "ID-123", repeat(64), repeat(65),
                "", "bad id", "line\nbreak", 42, null)) {
            String jsonId = new ObjectMapper().writeValueAsString(requestId);
            assertEquals("alice@example.com", service.reveal("{\"token\":\"" + TOKEN
                    + "\",\"revealRequestId\":" + jsonId + "}"));
        }
        assertEquals("alice@example.com", service.reveal(request()));
        verify(redis, times(10)).get(RedisRevealTokenStore.key(TOKEN));
        verify(crypto, times(10)).decryptWithCheckNoLog("cipher");
    }

    @Test
    public void invalidTokenTypesAndFormatsNeverAccessDependencies() {
        for (String input : Arrays.asList("{}", "{\"token\":null}", "{\"token\":1}",
                "{\"token\":true}", "{\"token\":[]}", "{\"token\":\"\"}",
                "{\"token\":\"" + TOKEN + "a\"}",
                "{\"token\":\"" + TOKEN.substring(1) + "\"}",
                "{\"token\":\"rt_" + repeat(42) + "+\"}")) {
            try {
                service.reveal(input);
                fail("Invalid token accepted: " + input);
            } catch (SensitiveRevealService.InvalidTokenException error) {
                assertNull(error.getCause());
            }
        }
        verifyZeroInteractions(redis, crypto);
    }

    @Test
    public void malformedRequestsAreRejectedBeforeRedis() {
        for (String input : Arrays.asList(null, "null", "[]", "invalid-json", "")) {
            try {
                service.reveal(input);
                fail("Malformed request accepted");
            } catch (IllegalArgumentException error) {
                assertNotNull(error.getMessage());
            }
        }
        verifyZeroInteractions(redis, crypto);
    }

    @Test
    public void missingTokenIsExpiredAndNeverDecrypted() {
        try {
            service.reveal(request());
            fail("Missing token must expire");
        } catch (SensitiveRevealService.ExpiredTokenException error) {
            assertNull(error.getCause());
        }
        verify(redis).get(RedisRevealTokenStore.key(TOKEN));
        verifyZeroInteractions(crypto);
    }

    @Test
    public void redisFailuresAreTranslatedWithoutLeakingCause() {
        when(redis.get(anyString())).thenReturn(ResultUtil.FAIL_RESULT)
                .thenThrow(new IllegalStateException("private backend detail"));
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                service.reveal(request());
                fail("Unavailable store must reject reveal");
            } catch (SensitiveRevealService.StoreUnavailableException error) {
                assertNull(error.getCause());
                assertNull(error.getMessage());
            }
        }
        verifyZeroInteractions(crypto);
    }

    @Test
    public void checkedAndRuntimeCryptoFailuresAreTranslatedWithoutLeakingCause() throws Exception {
        when(redis.get(anyString())).thenReturn("cipher");
        when(crypto.decryptWithCheckNoLog("cipher")).thenThrow(mock(CheckException.class))
                .thenThrow(new IllegalArgumentException("private ciphertext"));
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                service.reveal(request());
                fail("Decryption failure must reject reveal");
            } catch (SensitiveRevealService.RevealDecryptionException error) {
                assertNull(error.getCause());
                assertNull(error.getMessage());
            }
        }
    }

    private static String repeat(int count) {
        return String.join("", Collections.nCopies(count, "a"));
    }

    private static String request() {
        return "{\"token\":\"" + TOKEN + "\"}";
    }
}
