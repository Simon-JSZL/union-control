package com.union.control.service.sensitive;

import com.nucc.channel.ark.common.redis.RedisCacheService;
import com.nucc.channel.ark.common.util.ResultUtil;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class RedisRevealTokenStoreTest {
    private static final int TTL = 1800;
    private static final String TOKEN = "abc";
    private static final String KEY = "sensitive:reveal:"
            + "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private final RedisCacheService redis = mock(RedisCacheService.class);
    private final RedisRevealTokenStore store = new RedisRevealTokenStore(redis, TTL);

    @Test
    public void constructorChecksRedisAndBothTtlBounds() {
        try {
            new RedisRevealTokenStore(null, 1);
            fail("Missing Redis must be rejected");
        } catch (IllegalArgumentException error) {
            assertEquals("Redis service is required", error.getMessage());
        }
        for (long ttl : new long[]{Long.MIN_VALUE, -1, 0, (long) Integer.MAX_VALUE + 1}) {
            try {
                new RedisRevealTokenStore(redis, ttl);
                fail("Invalid TTL must be rejected: " + ttl);
            } catch (IllegalArgumentException error) {
                assertEquals("Reveal TTL is out of range", error.getMessage());
            }
        }
        assertNotNull(new RedisRevealTokenStore(redis, 1));
        assertNotNull(new RedisRevealTokenStore(redis, Integer.MAX_VALUE));
    }

    @Test
    public void emptyBatchSucceedsWithoutRedis() {
        assertTrue(store.putAll(Collections.emptyList()));
        verifyZeroInteractions(redis);
    }

    @Test
    public void batchesRespectTwentyEntryBoundaryAndPreserveEveryCiphertext() {
        when(redis.setexBatch(anyMap(), eq(TTL))).thenReturn(ResultUtil.SUCCESS_RESULT);
        List<RedisRevealTokenStore.Entry> entries = new ArrayList<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (int i = 0; i < 41; i++) {
            entries.add(new RedisRevealTokenStore.Entry("token-" + i, "cipher-" + i));
            expected.put(RedisRevealTokenStore.key("token-" + i), "cipher-" + i);
        }
        assertTrue(store.putAll(entries));
        ArgumentCaptor<Map> batches = ArgumentCaptor.forClass(Map.class);
        verify(redis, times(3)).setexBatch(batches.capture(), eq(TTL));
        List<Integer> sizes = new ArrayList<>();
        Map<String, String> actual = new LinkedHashMap<>();
        for (Map<String, String> batch : batches.getAllValues()) {
            sizes.add(batch.size());
            actual.putAll(batch);
        }
        assertEquals(Arrays.asList(20, 20, 1), sizes);
        assertEquals(expected, actual);
        assertEquals(KEY, RedisRevealTokenStore.key(TOKEN));
    }

    @Test
    public void unsuccessfulSecondBatchStopsBeforeThirdBatch() {
        for (String reply : Arrays.asList(ResultUtil.FAIL_RESULT, null, "unexpected")) {
            reset(redis);
            when(redis.setexBatch(anyMap(), eq(TTL)))
                    .thenReturn(ResultUtil.SUCCESS_RESULT).thenReturn(reply);
            assertFalse(store.putAll(Collections.nCopies(41,
                    new RedisRevealTokenStore.Entry(TOKEN, "cipher"))));
            verify(redis, times(2)).setexBatch(anyMap(), eq(TTL));
        }
    }

    @Test
    public void writeExceptionIsWrappedWithOriginalCause() {
        RuntimeException cause = new IllegalStateException("Redis unavailable");
        when(redis.setexBatch(anyMap(), eq(TTL))).thenThrow(cause);
        try {
            store.putAll(Collections.singletonList(new RedisRevealTokenStore.Entry(TOKEN, "cipher")));
            fail("Redis failure must propagate");
        } catch (RedisRevealTokenStore.StoreUnavailableException error) {
            assertSame(cause, error.getCause());
            assertEquals("Sensitive reveal store unavailable", error.getMessage());
        }
    }

    @Test
    public void readUsesHashedKeyAndAcceptsNullEmptyAndMaximumLength() {
        String maximum = String.join("", Collections.nCopies(12000, "x"));
        when(redis.get(KEY)).thenReturn(null, "", "cipher", maximum);
        assertNull(store.get(TOKEN));
        assertEquals("", store.get(TOKEN));
        assertEquals("cipher", store.get(TOKEN));
        assertEquals(maximum, store.get(TOKEN));
        verify(redis, times(4)).get(KEY);
    }

    @Test
    public void failureSentinelAndOversizedValueAreUnavailable() {
        for (String value : Arrays.asList(ResultUtil.FAIL_RESULT,
                String.join("", Collections.nCopies(12001, "x")))) {
            when(redis.get(KEY)).thenReturn(value);
            try {
                store.get(TOKEN);
                fail("Invalid Redis response must be rejected");
            } catch (RedisRevealTokenStore.StoreUnavailableException error) {
                assertEquals("Sensitive reveal store unavailable", error.getMessage());
                assertNull(error.getCause());
            }
        }
    }

    @Test
    public void readWrapsRuntimeExceptionButPreservesStoreException() {
        RuntimeException cause = new IllegalStateException("Redis unavailable");
        RedisRevealTokenStore.StoreUnavailableException unavailable =
                new RedisRevealTokenStore.StoreUnavailableException();
        when(redis.get(KEY)).thenThrow(cause).thenThrow(unavailable);
        try {
            store.get(TOKEN);
            fail("Redis failure must propagate");
        } catch (RedisRevealTokenStore.StoreUnavailableException error) {
            assertSame(cause, error.getCause());
        }
        try {
            store.get(TOKEN);
            fail("Store exception must propagate");
        } catch (RedisRevealTokenStore.StoreUnavailableException error) {
            assertSame(unavailable, error);
        }
    }
}
