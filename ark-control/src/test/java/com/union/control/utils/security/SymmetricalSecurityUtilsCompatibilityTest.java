package com.union.control.utils.security;

import com.epcc.commons.securityproxy.api.SecurityResult;
import com.epcc.commons.securityproxy.api.SymmetricalSecurityService;
import com.epcc.dubbo.result.Result;
import com.nucc.channel.ark.common.exception.CheckException;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SymmetricalSecurityUtilsCompatibilityTest {
    @Test
    public void longValuesUseTheConfiguredChunkSizeAndRoundTrip() throws Exception {
        RecordingProxy proxy = new RecordingProxy();
        SymmetricalSecurityUtils crypto = new SymmetricalSecurityUtils(proxy);

        String ciphertext = crypto.encryptLongString("abcdefgh", 3);

        assertEquals(3, proxy.encrypted.size());
        assertEquals("abc", proxy.encrypted.get(0));
        assertEquals("def", proxy.encrypted.get(1));
        assertEquals("gh", proxy.encrypted.get(2));
        assertEquals("abcdefgh", crypto.decryptLongString(ciphertext));
    }

    @Test
    public void taggedValuesKeepTheProductionDatabaseMarkerDirection() throws Exception {
        RecordingProxy proxy = new RecordingProxy();
        SymmetricalSecurityUtils crypto = new SymmetricalSecurityUtils(proxy);
        String plaintext = "手机13800138000 邮箱demo@example.com 电话010-12345678";

        String ciphertext = crypto.encryptWithTag(plaintext);

        assertTrue(ciphertext.contains("#["));
        assertEquals(3, occurrences(ciphertext, "#["));
        assertEquals(plaintext, crypto.decryptWithTag(ciphertext));
    }

    @Test
    public void proxyFailureKeepsTheProductionCheckedErrorContract() {
        SymmetricalSecurityUtils crypto = new SymmetricalSecurityUtils(new RecordingProxy() {
            @Override
            public Result<SecurityResult> encryptByFixedKey(Algorithm algorithm, byte[] plaintext) {
                return Result.failure("REMOTE_ERROR", "unavailable");
            }
        });

        try {
            crypto.encryptWithCheck("13800138000");
            throw new AssertionError("expected CheckException");
        } catch (CheckException error) {
            assertEquals("SYSTEM_INNER_ERROR", error.getCode());
            assertTrue(error.getMessage().contains("REMOTE_ERROR"));
        }
    }

    private static int occurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }

    static class RecordingProxy implements SymmetricalSecurityService {
        final List<String> encrypted = new ArrayList<>();

        @Override
        public Result<SecurityResult> encryptByFixedKey(Algorithm algorithm, byte[] plaintext) {
            assertEquals(Algorithm.AES256, algorithm);
            encrypted.add(new String(plaintext, StandardCharsets.UTF_8));
            byte[] result = new byte[plaintext.length + 1];
            result[0] = 42;
            System.arraycopy(plaintext, 0, result, 1, plaintext.length);
            return Result.success(new SecurityResult(result));
        }

        @Override
        public Result<SecurityResult> decryptByFixedKey(Algorithm algorithm, byte[] ciphertext) {
            assertEquals(Algorithm.AES256, algorithm);
            byte[] result = new byte[ciphertext.length - 1];
            System.arraycopy(ciphertext, 1, result, 0, result.length);
            return Result.success(new SecurityResult(result));
        }
    }
}
