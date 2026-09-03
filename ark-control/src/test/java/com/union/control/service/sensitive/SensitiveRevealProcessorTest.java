package com.union.control.service.sensitive;

import com.epcc.commons.securityproxy.api.SecurityResult;
import com.epcc.commons.securityproxy.api.SymmetricalSecurityService;
import com.epcc.dubbo.result.Result;
import com.nucc.channel.ark.common.exception.BaseDataErrorCode;
import com.nucc.channel.ark.common.redis.RedisCacheService;
import com.nucc.channel.ark.common.util.ResultUtil;
import com.union.control.utils.security.SymmetricalSecurityUtils;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SensitiveRevealProcessorTest {
    @Test
    public void masksEachMatchAndWritesOneCiphertextBatch() throws Exception {
        RecordingRedis redis = new RecordingRedis();
        SymmetricalSecurityUtils crypto = new SymmetricalSecurityUtils(new PrefixGateway());
        SensitiveRevealProcessor processor = new SensitiveRevealProcessor(
                crypto, new RedisRevealTokenStore(redis, 300));
        Map<String, Object> row = row(crypto, "手机13800138000，邮箱demo@example.com");

        processor.process(row);

        String value = (String) row.get("phoneNumber");
        assertTrue(value, value.matches("手机\\[#138\\*{4}8000#VIEW:rt_[A-Za-z0-9_-]{43}\\]，"
                + "邮箱\\[#d\\*{3}@example\\.com#VIEW:rt_[A-Za-z0-9_-]{43}\\]"));
        assertEquals(2, redis.values.size());
        assertTrue(redis.values.get(0).startsWith("ZW5j"));
        assertFalse(redis.values.get(0).contains("13800138000"));
    }

    @Test
    public void storeFailureReturnsMaskedValuesWithoutTokens() throws Exception {
        RecordingRedis redis = new RecordingRedis();
        redis.succeed = false;
        SymmetricalSecurityUtils crypto = new SymmetricalSecurityUtils(new PrefixGateway());
        SensitiveRevealProcessor processor = new SensitiveRevealProcessor(
                crypto, new RedisRevealTokenStore(redis, 300));
        Map<String, Object> row = row(crypto, "手机13800138000");

        processor.process(row);

        assertEquals("手机[#138****8000]", row.get("phoneNumber"));
    }

    @Test
    public void fragmentEncryptionFailureNeverPublishesAnUnstoredToken() {
        RecordingRedis redis = new RecordingRedis();
        SensitiveRevealProcessor processor = new SensitiveRevealProcessor(
                new SymmetricalSecurityUtils(new FailingEncryptGateway()),
                new RedisRevealTokenStore(redis, 300));
        Map<String, Object> row = new HashMap<>();
        row.put("phoneNumber", Base64.getEncoder().encodeToString(
                "enc:13800138000".getBytes(StandardCharsets.UTF_8)));

        processor.process(row);

        assertEquals("[#138****8000]", row.get("phoneNumber"));
        assertTrue(redis.values.isEmpty());
    }

    private static Map<String, Object> row(SymmetricalSecurityUtils crypto, String plaintext)
            throws Exception {
        Map<String, Object> row = new HashMap<>();
        row.put("phoneNumber", crypto.encryptWithCheck(plaintext));
        return row;
    }

    static class RecordingRedis extends RedisCacheService {
        boolean succeed = true;
        final List<String> values = new ArrayList<>();

        @Override
        public String setex(String key, int time, String value) {
            values.add(value);
            return succeed ? ResultUtil.SUCCESS_RESULT : ResultUtil.FAIL_RESULT;
        }
    }

    static class PrefixGateway implements SymmetricalSecurityService {
        @Override
        public Result<SecurityResult> encryptByFixedKey(Algorithm algorithm, byte[] plaintext) {
            return Result.success(new SecurityResult(
                    ("enc:" + new String(plaintext, StandardCharsets.UTF_8))
                            .getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public Result<SecurityResult> decryptByFixedKey(Algorithm algorithm, byte[] ciphertext) {
            return Result.success(new SecurityResult(
                    java.util.Arrays.copyOfRange(ciphertext, 4, ciphertext.length)));
        }
    }

    static class FailingEncryptGateway extends PrefixGateway {
        @Override
        public Result<SecurityResult> encryptByFixedKey(Algorithm algorithm, byte[] plaintext) {
            return Result.failure(BaseDataErrorCode.SYSTEM_INNER_ERROR.getCode(), "unavailable");
        }
    }
}
