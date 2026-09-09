package com.union.control.service.sensitive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nucc.channel.ark.common.exception.CheckException;
import com.union.control.utils.security.SymmetricalSecurityUtils;
import com.union.control.utils.AgentSupport;
import com.union.control.service.sensitive.SensitiveRevealService.ExpiredTokenException;
import com.union.control.service.sensitive.SensitiveRevealService.InvalidTokenException;
import com.union.control.service.sensitive.SensitiveRevealService.RevealDecryptionException;
import com.union.control.service.sensitive.SensitiveRevealService.StoreUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Pattern;

@Service("sensitiveRevealService")
public final class SensitiveRevealServiceImpl implements SensitiveRevealService {
    private static final Logger LOG = LoggerFactory.getLogger(SensitiveRevealServiceImpl.class);
    private static final Pattern TOKEN = Pattern.compile("rt_[A-Za-z0-9_-]{43}");
    private final RedisRevealTokenStore tokens;
    private final SymmetricalSecurityUtils crypto;
    private final ObjectMapper json;

    public SensitiveRevealServiceImpl(RedisRevealTokenStore tokens, SymmetricalSecurityUtils crypto,
                                  ObjectMapper json) {
        this.tokens = tokens;
        this.crypto = crypto;
        this.json = json;
    }

    public String reveal(String input) {
        Map<String, Object> request = AgentSupport.request(json, input);
        Object rawToken = request.get("token");
        String token = rawToken instanceof String ? (String) rawToken : null;
        if (token == null || !TOKEN.matcher(token).matches()) throw new InvalidTokenException();
        String ciphertext;
        try {
            ciphertext = tokens.get(token);
        } catch (RedisRevealTokenStore.StoreUnavailableException error) {
            throw new StoreUnavailableException();
        }
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

}
