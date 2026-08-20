package com.union.control.service.sensitive;

import com.nucc.channel.ark.common.redis.RedisCacheService;
import com.nucc.channel.ark.common.util.ResultUtil;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RedisRevealTokenStoreTest {
    @Test
    public void storesCiphertextThroughProductionRedisContract() {
        RecordingRedis redis = new RecordingRedis();
        RedisRevealTokenStore store = new RedisRevealTokenStore(redis, 300);

        assertTrue(store.putAll(Arrays.asList(
                new RedisRevealTokenStore.Entry("rt_A", "cipher-a"),
                new RedisRevealTokenStore.Entry("rt_B", "cipher-b"))));
        assertEquals(2, redis.values.size());
        assertEquals(Integer.valueOf(300), redis.ttls.get(RedisRevealTokenStore.key("rt_A")));
        assertEquals("cipher-a", store.get("rt_A"));
    }

    @Test
    public void redisFailureDoesNotPublishRevealTokens() {
        RecordingRedis redis = new RecordingRedis();
        redis.failWrites = true;

        assertFalse(new RedisRevealTokenStore(redis, 300).putAll(Arrays.asList(
                new RedisRevealTokenStore.Entry("rt_A", "cipher-a"))));
    }

    @Test(expected = RedisRevealTokenStore.StoreUnavailableException.class)
    public void redisReadFailureIsNotReportedAsAnExpiredToken() {
        RecordingRedis redis = new RecordingRedis();
        redis.failReads = true;

        new RedisRevealTokenStore(redis, 300).get("rt_A");
    }

    static final class RecordingRedis extends RedisCacheService {
        final Map<String, String> values = new HashMap<>();
        final Map<String, Integer> ttls = new HashMap<>();
        boolean failWrites;
        boolean failReads;

        @Override
        public String setex(String key, int time, String value) {
            if (failWrites) return ResultUtil.FAIL_RESULT;
            values.put(key, value);
            ttls.put(key, time);
            return ResultUtil.SUCCESS_RESULT;
        }

        @Override
        public String get(String key) {
            return failReads ? ResultUtil.FAIL_RESULT : values.get(key);
        }
    }
}
