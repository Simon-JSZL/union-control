package com.union.control.service.sensitive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nucc.channel.ark.common.exception.CheckException;
import com.union.control.utils.security.SymmetricalSecurityUtils;
import com.union.control.utils.ServiceSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Pattern;

@Service
public final class SensitiveRevealService {
    private static final Logger LOG = LoggerFactory.getLogger(SensitiveRevealService.class);
    private static final Pattern TOKEN = Pattern.compile("rt_[A-Za-z0-9_-]{43}");
    private final RedisRevealTokenStore tokens;
    private final SymmetricalSecurityUtils crypto;
    private final ObjectMapper json;

    public SensitiveRevealService(RedisRevealTokenStore tokens, SymmetricalSecurityUtils crypto,
                                  ObjectMapper json) {
        this.tokens = tokens;
        this.crypto = crypto;
        this.json = json;
    }

    public String reveal(String input) {
        Map<String, Object> request = ServiceSupport.request(json, input);
        Object rawToken = request.get("token");
        String token = rawToken instanceof String ? (String) rawToken : null;
        if (token == null || !TOKEN.matcher(token).matches()) throw new InvalidTokenException();
        String ciphertext = tokens.get(token);
        if (ciphertext == null) throw new ExpiredTokenException();
        try {
            String plaintext = crypto.decryptWithCheckNoLog(ciphertext);
            LOG.info("Sensitive reveal completed");
            return plaintext;
        } catch (CheckException | RuntimeException error) {
            LOG.warn("Sensitive reveal decryption failed error_type={}",
                    error.getClass().getSimpleName());
            throw new RevealDecryptionException();
        }
    }

    public static class InvalidTokenException extends RuntimeException {}
    public static class ExpiredTokenException extends RuntimeException {}
    public static class RevealDecryptionException extends RuntimeException {}
}
