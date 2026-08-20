package com.union.control.local.sensitive;

import com.nucc.channel.ark.common.redis.RedisCacheService;
import com.nucc.channel.ark.common.util.ResultUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Local-only in-memory boundary for the production RedisCacheService contract. */
public final class LocalRedisCacheService extends RedisCacheService {
    private final Map<String, Value> values = new ConcurrentHashMap<>();

    @Override
    public String setex(String key, int time, String value) {
        values.put(key, new Value(value, System.currentTimeMillis() + time * 1000L));
        return ResultUtil.SUCCESS_RESULT;
    }

    @Override
    public String get(String key) {
        Value value = values.get(key);
        if (value == null) return null;
        if (value.expiresAt <= System.currentTimeMillis()) {
            values.remove(key, value);
            return null;
        }
        return value.text;
    }

    @Override
    public String del(String key) {
        values.remove(key);
        return ResultUtil.SUCCESS_RESULT;
    }

    private static final class Value {
        final String text;
        final long expiresAt;

        Value(String text, long expiresAt) {
            this.text = text;
            this.expiresAt = expiresAt;
        }
    }
}
