package com.union.control.service.sensitive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nucc.channel.ark.common.redis.RedisCacheService;
import com.nucc.channel.ark.common.util.ResultUtil;
import com.nucc.channel.ark.common.exception.CheckException;
import com.union.control.utils.security.SymmetricalSecurityUtils;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class SensitiveRevealServiceImplTest {
    private final RedisCacheService redis = mock(RedisCacheService.class);
    private final RedisRevealTokenStore tokens = new RedisRevealTokenStore(redis, 1800);
    private final SymmetricalSecurityUtils crypto = mock(SymmetricalSecurityUtils.class);
    private final SensitiveRevealServiceImpl service = new SensitiveRevealServiceImpl(tokens, crypto, new ObjectMapper());
    private static final String TOKEN = "rt_" + String.join("", java.util.Collections.nCopies(43, "a"));

    @Test public void assertPlaintextAndRequestIdVariants() throws Exception {
        when(redis.get(anyString())).thenReturn("cipher");
        when(crypto.decryptWithCheckNoLog("cipher")).thenReturn("13812345678");
        for (String id : new String[]{"\"valid-ID\"", "\"invalid id\"", "42", "null", "\"\""}) {
            assertEquals("13812345678", service.reveal("{\"token\":\"" + TOKEN + "\",\"revealRequestId\":" + id + "}"));
        }
        verify(redis, times(5)).get(RedisRevealTokenStore.key(TOKEN));
    }

    @Test public void assertInvalidTokensNeverReachStore() {
        for (String input : new String[]{"{}", "{\"token\":42}", "{\"token\":null}", "{\"token\":\"bad\"}", "{\"token\":\"" + TOKEN + "a\"}"}) {
            assertFailure(SensitiveRevealService.InvalidTokenException.class, input);
        }
        verifyZeroInteractions(redis, crypto);
    }

    @Test public void assertMalformedRequestsRejected() {
        for (String input : new String[]{null, "null", "[]", "broken"}) assertFailure(IllegalArgumentException.class, input);
        verifyZeroInteractions(redis, crypto);
    }

    @Test public void assertExpiredToken() {
        assertFailure(SensitiveRevealService.ExpiredTokenException.class, request());
        verifyZeroInteractions(crypto);
    }

    @Test public void assertStoreFailureTranslated() {
        when(redis.get(anyString())).thenReturn(ResultUtil.FAIL_RESULT);
        assertFailure(SensitiveRevealService.StoreUnavailableException.class, request());
        verifyZeroInteractions(crypto);
    }

    @Test public void assertCheckedAndRuntimeDecryptionFailuresTranslated() throws Exception {
        when(redis.get(anyString())).thenReturn("cipher");
        when(crypto.decryptWithCheckNoLog("cipher")).thenThrow(mock(CheckException.class)).thenThrow(new IllegalStateException("secret"));
        assertFailure(SensitiveRevealService.RevealDecryptionException.class, request());
        assertFailure(SensitiveRevealService.RevealDecryptionException.class, request());
    }

    private String request() { return "{\"token\":\"" + TOKEN + "\"}"; }
    private void assertFailure(Class<? extends Throwable> type, String input) {
        try { service.reveal(input); fail("Expected " + type.getSimpleName()); }
        catch (RuntimeException error) { assertEquals(type, error.getClass()); }
    }
}
