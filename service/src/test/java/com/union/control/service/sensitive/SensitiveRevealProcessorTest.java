package com.union.control.service.sensitive;

import com.epcc.commons.securityproxy.api.SecurityResult;
import com.epcc.commons.securityproxy.api.SymmetricalSecurityService;
import com.epcc.dubbo.result.Result;
import com.nucc.channel.ark.common.redis.RedisCacheService;
import com.nucc.channel.ark.common.util.ResultUtil;
import com.union.control.utils.security.SymmetricalSecurityUtils;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SensitiveRevealProcessorTest {
    @Test
    public void masksEachMatchAndWritesOneCiphertextBatch() {
        RecordingRedis redis = new RecordingRedis();
        SensitiveRevealProcessor processor = new SensitiveRevealProcessor(
                new SymmetricalSecurityUtils(new PrefixGateway()),
                new RedisRevealTokenStore(redis, 300));

        String value = processor.maskText("手机13800138000，邮箱demo@example.com");

        assertTrue(value, value.matches("手机\\[#138\\*{4}8000#VIEW:rt_[A-Za-z0-9_-]{43}\\]，"
                + "邮箱\\[#d\\*{3}@example\\.com#VIEW:rt_[A-Za-z0-9_-]{43}\\]"));
        assertEquals(2, redis.values.size());
        assertTrue(redis.values.get(0).startsWith("ZW5j"));
        assertFalse(redis.values.get(0).contains("13800138000"));
    }

    @Test
    public void storeFailureReturnsMaskedValuesWithoutTokens() {
        RecordingRedis redis = new RecordingRedis();
        redis.succeed = false;
        SensitiveRevealProcessor processor = new SensitiveRevealProcessor(
                new SymmetricalSecurityUtils(new PrefixGateway()),
                new RedisRevealTokenStore(redis, 300));

        assertEquals("手机[#138****8000]", processor.maskText("手机13800138000"));
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
            return Result.success(new SecurityResult(new byte[0]));
        }
    }
}
