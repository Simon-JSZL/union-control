package com.union.control.sensitive.reveal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public interface RevealTokenStore {
    boolean putAll(String userId, List<Entry> entries);
    String getCiphertext(String userId, String token);

    class Entry {
        final String token;
        final String ciphertext;

        public Entry(String token, String ciphertext) {
            this.token = token;
            this.ciphertext = ciphertext;
        }

        @Override
        public String toString() {
            return "RevealEntry{ciphertextLength=" + ciphertext.length() + "}";
        }
    }
}

@Component
class RedisRevealTokenStore implements RevealTokenStore {
    private static final byte[] PREFIX = "reveal:".getBytes(StandardCharsets.UTF_8);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final long ttlSeconds;

    RedisRevealTokenStore(StringRedisTemplate redis, ObjectMapper json,
                          @Value("${sensitive.reveal-ttl-seconds:300}") long ttlSeconds) {
        this.redis = redis;
        this.json = json;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public boolean putAll(final String userId, final List<Entry> entries) {
        if (entries.isEmpty()) return true;
        try {
            final List<String> keys = new ArrayList<>();
            final List<String> values = new ArrayList<>();
            for (Entry entry : entries) {
                keys.add(new String(key(userId, entry.token), StandardCharsets.UTF_8));
                values.add(new String(value(entry.ciphertext), StandardCharsets.UTF_8));
            }
            redis.executePipelined(new RedisCallback<Object>() {
                @Override
                public Object doInRedis(RedisConnection connection) throws DataAccessException {
                    for (int i = 0; i < entries.size(); i++)
                        connection.set(keys.get(i).getBytes(StandardCharsets.UTF_8),
                                values.get(i).getBytes(StandardCharsets.UTF_8),
                                Expiration.seconds(ttlSeconds), SetOption.SET_IF_ABSENT);
                    return null;
                }
            });
            return values.equals(redis.opsForValue().multiGet(keys));
        } catch (RuntimeException error) {
            throw new StoreUnavailableException(error);
        }
    }

    @Override
    public String getCiphertext(String userId, String token) {
        try {
            String raw = redis.opsForValue().get(
                    new String(key(userId, token), StandardCharsets.UTF_8));
            if (raw == null) return null;
            if (raw.length() > 12000) throw new StoreUnavailableException();
            Object ciphertext = json.readValue(raw, Map.class).get("ciphertext");
            if (!(ciphertext instanceof String)) throw new StoreUnavailableException();
            return (String) ciphertext;
        } catch (StoreUnavailableException error) {
            throw error;
        } catch (Exception error) {
            throw new StoreUnavailableException(error);
        }
    }

    private byte[] value(String ciphertext) {
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("version", 1);
            value.put("ciphertext", ciphertext);
            return json.writeValueAsBytes(value);
        } catch (Exception error) {
            throw new StoreUnavailableException(error);
        }
    }

    static byte[] key(String userId, String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(userId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            byte[] key = new byte[PREFIX.length + hashed.length * 2];
            System.arraycopy(PREFIX, 0, key, 0, PREFIX.length);
            for (int i = 0; i < hashed.length; i++) {
                int value = hashed[i] & 0xff;
                key[PREFIX.length + i * 2] = hex(value >>> 4);
                key[PREFIX.length + i * 2 + 1] = hex(value & 0x0f);
            }
            return key;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte hex(int value) {
        return (byte) "0123456789abcdef".charAt(value);
    }
}
