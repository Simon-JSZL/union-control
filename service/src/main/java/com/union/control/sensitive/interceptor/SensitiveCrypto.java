package com.union.control.sensitive.interceptor;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Existing production AES boundary. The local implementation only simulates that dependency. */
public interface SensitiveCrypto {
    String encrypt(String plaintext);
    String decrypt(String ciphertext);
}

/** Local demo only; production supplies its existing AES implementation. */
@Component
class MockSensitiveCrypto implements SensitiveCrypto {
    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty() || plaintext.length() > 4096)
            throw new IllegalArgumentException("敏感值不能为空且长度不能超过4096字符");
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(plaintext.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty() || ciphertext.length() > 8192)
            throw new IllegalArgumentException("Invalid ciphertext");
        return new String(Base64.getUrlDecoder().decode(ciphertext), StandardCharsets.UTF_8);
    }
}
