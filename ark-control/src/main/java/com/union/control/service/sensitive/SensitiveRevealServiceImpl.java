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
import java.util.UUID;

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
        long started = System.nanoTime();
        Map<String, Object> request = AgentSupport.request(json, input);
        Object rawRequestId = request.get("revealRequestId");
        String requestId = rawRequestId instanceof String
                && ((String) rawRequestId).matches("[A-Za-z0-9-]{1,64}")
                ? (String) rawRequestId : UUID.randomUUID().toString();
        LOG.info("Sensitive reveal Control received request_id={}", requestId);
        Object rawToken = request.get("token");
        String token = rawToken instanceof String ? (String) rawToken : null;
        if (token == null || !TOKEN.matcher(token).matches()) {
            LOG.info("Sensitive reveal Control rejected request_id={} reason=invalid_token", requestId);
            throw new InvalidTokenException();
        }
        String ciphertext;
        try {
            ciphertext = tokens.get(token);
        } catch (RedisRevealTokenStore.StoreUnavailableException error) {
            LOG.info("Sensitive reveal Control failed request_id={} stage=token_lookup reason=store_unavailable", requestId);
            throw new StoreUnavailableException();
        }
        if (ciphertext == null) {
            LOG.info("Sensitive reveal Control rejected request_id={} reason=token_expired", requestId);
            throw new ExpiredTokenException();
        }
        LOG.info("Sensitive reveal Control token resolved request_id={}", requestId);
        try {
            String plaintext = crypto.decryptWithCheckNoLog(ciphertext);
            LOG.info("Sensitive reveal Control completed request_id={} elapsed_ms={}",
                    requestId, (System.nanoTime() - started) / 1000000);
            return plaintext;
        } catch (CheckException | RuntimeException error) {
            LOG.info("Sensitive reveal Control failed request_id={} stage=decrypt error_type={}",
                    requestId, error.getClass().getSimpleName());
            throw new RevealDecryptionException();
        }
    }

}
