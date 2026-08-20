package com.union.control.service.sensitive;

import com.nucc.channel.ark.common.redis.RedisCacheService;
import com.nucc.channel.ark.common.util.ResultUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

public final class RedisRevealTokenStore {
    private static final byte[] PREFIX = "sensitive:reveal:".getBytes(StandardCharsets.US_ASCII);
    private final RedisCacheService redis;
    private final int ttlSeconds;

    public RedisRevealTokenStore(RedisCacheService redis, long ttlSeconds) {
        if (redis == null) throw new IllegalArgumentException("Redis service is required");
        if (ttlSeconds <= 0 || ttlSeconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Reveal TTL is out of range");
        }
        this.redis = redis;
        this.ttlSeconds = (int) ttlSeconds;
    }

    boolean putAll(List<Entry> entries) {
        if (entries.isEmpty()) return true;
        try {
            for (Entry entry : entries) {
                if (!ResultUtil.SUCCESS_RESULT.equals(
                        redis.setex(key(entry.token), ttlSeconds, entry.ciphertext))) return false;
            }
            return true;
        } catch (RuntimeException error) {
            throw new StoreUnavailableException(error);
        }
    }

    String get(String token) {
        try {
            String ciphertext = redis.get(key(token));
            if (ResultUtil.FAIL_RESULT.equals(ciphertext)) {
                throw new StoreUnavailableException();
            }
            if (ciphertext != null && ciphertext.length() > 12000) {
                throw new StoreUnavailableException();
            }
            return ciphertext;
        } catch (StoreUnavailableException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new StoreUnavailableException(error);
        }
    }

    static String key(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder(new String(PREFIX, StandardCharsets.US_ASCII));
            for (byte value : hash) key.append(String.format("%02x", value & 0xff));
            return key.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static final class Entry {
        final String token;
        final String ciphertext;

        Entry(String token, String ciphertext) {
            this.token = token;
            this.ciphertext = ciphertext;
        }
    }

    public static final class StoreUnavailableException extends RuntimeException {
        StoreUnavailableException() {
            super("Sensitive reveal store unavailable");
        }

        StoreUnavailableException(Throwable cause) {
            super("Sensitive reveal store unavailable", cause);
        }
    }
}
