package com.union.control.local.sensitive;

import com.epcc.commons.securityproxy.api.SecurityResult;
import com.epcc.commons.securityproxy.api.SymmetricalSecurityService;
import com.epcc.dubbo.result.Result;
import com.nucc.channel.ark.common.exception.BaseDataErrorCode;
import com.nucc.channel.ark.common.exception.CheckException;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Local-only stand-in for the unavailable sensitiveProxy AES256 service.
 * It is intentionally recognizable and must never be used as production cryptography.
 */
public final class LocalMockSensitiveProxy implements SymmetricalSecurityService {
    private static final byte[] PREFIX = "LOCAL_MOCK_ONLY:".getBytes(StandardCharsets.US_ASCII);

    @Override
    public Result<SecurityResult> encryptByFixedKey(Algorithm algorithm, byte[] plaintext) {
        byte[] result = Arrays.copyOf(PREFIX, PREFIX.length + plaintext.length);
        System.arraycopy(plaintext, 0, result, PREFIX.length, plaintext.length);
        return Result.success(new SecurityResult(result));
    }

    @Override
    public Result<SecurityResult> decryptByFixedKey(Algorithm algorithm, byte[] ciphertext) {
        if (ciphertext.length < PREFIX.length) return invalid();
        for (int i = 0; i < PREFIX.length; i++) {
            if (ciphertext[i] != PREFIX[i]) return invalid();
        }
        return Result.success(new SecurityResult(
                Arrays.copyOfRange(ciphertext, PREFIX.length, ciphertext.length)));
    }

    private static Result<SecurityResult> invalid() {
        return Result.failure(BaseDataErrorCode.SYSTEM_INNER_ERROR.getCode(),
                "Local mock ciphertext rejected");
    }
}
