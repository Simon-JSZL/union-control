package com.union.control.sensitive.reveal;

import com.union.control.sensitive.interceptor.SensitiveCrypto;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class SensitiveRevealService {
    private static final Pattern TOKEN = Pattern.compile("rt_[A-Za-z0-9_-]{43}");

    private final RevealTokenStore tokens;
    private final SensitiveCrypto crypto;

    public SensitiveRevealService(RevealTokenStore tokens, SensitiveCrypto crypto) {
        this.tokens = tokens;
        this.crypto = crypto;
    }

    public String reveal(String userId, String token) {
        if (token == null || !TOKEN.matcher(token).matches()) throw new InvalidTokenException();
        String ciphertext = tokens.getCiphertext(userId, token);
        if (ciphertext == null) throw new ExpiredTokenException();
        try {
            return crypto.decrypt(ciphertext);
        } catch (RuntimeException error) {
            throw new DecryptionException();
        }
    }

    public static class InvalidTokenException extends RuntimeException {}
    public static class ExpiredTokenException extends RuntimeException {}
    public static class DecryptionException extends RuntimeException {}
}
