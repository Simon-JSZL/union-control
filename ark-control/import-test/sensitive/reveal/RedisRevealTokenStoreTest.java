package com.union.control.service.sensitive;

import com.nucc.channel.ark.common.redis.RedisCacheService;
import com.nucc.channel.ark.common.util.ResultUtil;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.Matchers.*;

public class RedisRevealTokenStoreTest {
    private final RedisCacheService redis = mock(RedisCacheService.class);
    private final RedisRevealTokenStore store = new RedisRevealTokenStore(redis, 1800);

    @Test public void assertConstructorBounds() {
        for (long ttl : new long[]{0, -1, (long) Integer.MAX_VALUE + 1}) {
            try { new RedisRevealTokenStore(redis, ttl); fail("Invalid TTL accepted"); }
            catch (IllegalArgumentException error) { assertEquals("Reveal TTL is out of range", error.getMessage()); }
        }
        try { new RedisRevealTokenStore(null, 1); fail("Null Redis accepted"); }
        catch (IllegalArgumentException error) { assertEquals("Redis service is required", error.getMessage()); }
        assertNotNull(new RedisRevealTokenStore(redis, 1));
        assertNotNull(new RedisRevealTokenStore(redis, Integer.MAX_VALUE));
    }

    @Test public void assertEmptyBatchDoesNotCallRedis() {
        assertTrue(store.putAll(Collections.emptyList()));
        verifyZeroInteractions(redis);
    }

    @Test public void assertBatchBoundaryHashAndTtl() {
        when(redis.setexBatch(anyMap(), eq(1800))).thenReturn(ResultUtil.SUCCESS_RESULT);
        List<RedisRevealTokenStore.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 41; i++) entries.add(new RedisRevealTokenStore.Entry("token-" + i, "cipher-" + i));
        assertTrue(store.putAll(entries));
        ArgumentCaptor<Map> batches = ArgumentCaptor.forClass(Map.class);
        verify(redis, times(3)).setexBatch(batches.capture(), eq(1800));
        assertEquals(Arrays.asList(20,20,1), Arrays.asList(batches.getAllValues().get(0).size(), batches.getAllValues().get(1).size(), batches.getAllValues().get(2).size()));
        Map<String,String> combined = new LinkedHashMap<>();
        for (Map batch : batches.getAllValues()) combined.putAll(batch);
        for (int i = 0; i < 41; i++) assertEquals("cipher-" + i, combined.get(RedisRevealTokenStore.key("token-" + i)));
        assertEquals("sensitive:reveal:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", RedisRevealTokenStore.key("abc"));
    }

    @Test public void assertUnsuccessfulBatchStopsWrites() {
        for (String result : new String[]{ResultUtil.FAIL_RESULT, null}) {
            when(redis.setexBatch(anyMap(), anyInt())).thenReturn(result);
            assertFalse(store.putAll(Collections.nCopies(21, new RedisRevealTokenStore.Entry("t", "c"))));
        }
        verify(redis, times(2)).setexBatch(anyMap(), anyInt());
    }

    @Test public void assertWriteFailurePreservesCause() {
        RuntimeException cause = new IllegalStateException("offline");
        when(redis.setexBatch(anyMap(), anyInt())).thenThrow(cause);
        try { store.putAll(Collections.singletonList(new RedisRevealTokenStore.Entry("t", "c"))); fail("Expected failure"); }
        catch (RedisRevealTokenStore.StoreUnavailableException error) { assertSame(cause, error.getCause()); }
    }

    @Test public void assertReadValuesAndLengthBoundary() {
        String limit = String.join("", Collections.nCopies(12000, "a"));
        when(redis.get(RedisRevealTokenStore.key("t"))).thenReturn(null, "", "cipher", limit);
        assertNull(store.get("t"));
        assertEquals("", store.get("t"));
        assertEquals("cipher", store.get("t"));
        assertEquals(limit, store.get("t"));
    }

    @Test public void assertReadFailures() {
        for (String value : new String[]{ResultUtil.FAIL_RESULT, String.join("", Collections.nCopies(12001,"a"))}) {
            when(redis.get(anyString())).thenReturn(value);
            try { store.get("t"); fail("Expected failure"); }
            catch (RedisRevealTokenStore.StoreUnavailableException error) { assertEquals("Sensitive reveal store unavailable", error.getMessage()); assertNull(error.getCause()); }
        }
        RuntimeException cause = new IllegalStateException("offline");
        when(redis.get(anyString())).thenThrow(cause);
        try { store.get("t"); fail("Expected failure"); }
        catch (RedisRevealTokenStore.StoreUnavailableException error) { assertSame(cause, error.getCause()); }
        RedisRevealTokenStore.StoreUnavailableException original = new RedisRevealTokenStore.StoreUnavailableException();
        doThrow(original).when(redis).get(anyString());
        try { store.get("t"); fail("Expected failure"); }
        catch (RedisRevealTokenStore.StoreUnavailableException error) { assertSame(original, error); }
    }

}
