package com.union.control.sensitive.reveal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.sensitive.interceptor.SensitiveCrypto;
import com.union.control.utils.ServiceSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;
import java.util.Map;

@Service
public class SensitiveRevealService {
    private static final Logger LOG = LoggerFactory.getLogger(SensitiveRevealService.class);
    private static final Pattern TOKEN = Pattern.compile("rt_[A-Za-z0-9_-]{43}");

    private final RevealTokenStore tokens;
    private final SensitiveCrypto crypto;
    private final ObjectMapper json;

    public SensitiveRevealService(RevealTokenStore tokens, SensitiveCrypto crypto, ObjectMapper json) {
        this.tokens = tokens;
        this.crypto = crypto;
        this.json = json;
    }

    public String reveal(String input) {
        Map<String, Object> request = ServiceSupport.request(json, input);
        String userId = ServiceSupport.userId(request);
        Object rawToken = request.get("token");
        String token = rawToken instanceof String ? (String) rawToken : null;
        if (token == null || !TOKEN.matcher(token).matches()) throw new InvalidTokenException();
        String ciphertext = tokens.getCiphertext(userId, token);
        if (ciphertext == null) throw new ExpiredTokenException();
        try {
            String plaintext = crypto.decrypt(ciphertext);
            LOG.info("Sensitive field reveal decryption completed");
            return plaintext;
        } catch (RuntimeException error) {
            LOG.warn("Sensitive field reveal decryption failed error_type={}",
                    error.getClass().getSimpleName());
            throw new DecryptionException();
        }
    }

    public static class InvalidTokenException extends RuntimeException {}
    public static class ExpiredTokenException extends RuntimeException {}
    public static class DecryptionException extends RuntimeException {}
}
