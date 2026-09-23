package com.union.control.utils.security;

import com.epcc.commons.securityproxy.api.SecurityResult;
import com.epcc.commons.securityproxy.api.SymmetricalSecurityService;
import com.epcc.dubbo.result.Result;
import com.nucc.channel.ark.common.exception.BaseDataErrorCode;
import com.nucc.channel.ark.common.exception.CheckException;
import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.nucc.channel.ark.common.util.Constant.ENCRYPT_END;
import static com.nucc.channel.ark.common.util.Constant.ENCRYPT_START;
import static com.nucc.channel.ark.common.util.Constant.REGEX_DECRYPT_TAG;
import static com.nucc.channel.ark.common.util.Constant.REGEX_EMAIL;
import static com.nucc.channel.ark.common.util.Constant.REGEX_MOBILE;
import static com.nucc.channel.ark.common.util.Constant.REGEX_TELEPHONE;

/** Production-equivalent crypto utility; cryptography remains behind sensitiveProxy. */
@Component
public class SymmetricalSecurityUtils {
    private static final Logger LOG = LoggerFactory.getLogger(SymmetricalSecurityUtils.class);
    private static final Pattern SENSITIVE = Pattern.compile(
            "(" + REGEX_EMAIL + ")|(" + REGEX_MOBILE + ")|(" + REGEX_TELEPHONE + ")");
    private static final Pattern DECRYPT_TAG = Pattern.compile(REGEX_DECRYPT_TAG);

    @Resource(name = "sensitiveProxy")
    private SymmetricalSecurityService symmetricalSecurityService;

    public SymmetricalSecurityUtils() {}

    public SymmetricalSecurityUtils(SymmetricalSecurityService symmetricalSecurityService) {
        this.symmetricalSecurityService = symmetricalSecurityService;
    }

    public String encryptLongString(String plaintext, int chunkSize) throws CheckException {
        if (chunkSize < 0) throw new IllegalArgumentException("Negative sensitive chunk size");
        if (chunkSize == 0) return encryptByFixKey(plaintext);
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < plaintext.length();) {
            int end = i + Math.min(chunkSize, plaintext.length() - i);
            if (end < plaintext.length() && Character.isHighSurrogate(plaintext.charAt(end - 1))
                    && Character.isLowSurrogate(plaintext.charAt(end))) end++;
            chunks.add(encryptByFixKey(plaintext.substring(i, end)));
            i = end;
        }
        return join(chunks, "|");
    }

    public String encryptWithTag(String plaintext) throws CheckException {
        if (isBlank(plaintext)) return "";
        return replaceEncrypted(SENSITIVE, plaintext);
    }

    public String decryptLongString(String ciphertext) throws CheckException {
        String[] chunks = ciphertext.split("\\|");
        StringBuilder result = new StringBuilder();
        for (String chunk : chunks) result.append(decryptWithCheckNoLog(chunk));
        return result.toString();
    }

    public String decryptWithTag(String ciphertext) throws CheckException {
        if (isBlank(ciphertext)) return "";
        StringBuilder value = new StringBuilder(ciphertext);
        Matcher matcher = DECRYPT_TAG.matcher(value);
        while (matcher.find()) {
            value.replace(matcher.start(), matcher.end(), decryptWithCheckNoLog(matcher.group(1)));
            matcher.reset(value);
        }
        return value.toString();
    }

    public String encryptWithCheck(String plaintext) throws CheckException {
        return isBlank(plaintext) ? plaintext : encryptByFixKey(plaintext);
    }

    public String decryptWithCheck(String ciphertext) {
        if (isBlank(ciphertext)) return ciphertext;
        try {
            return decryptByFixKey(ciphertext);
        } catch (CheckException error) {
            LOG.error("decryptWithCheck failed error_type={}", error.getClass().getSimpleName());
            return ciphertext;
        }
    }

    public String decryptWithCheckNoLog(String ciphertext) throws CheckException {
        return isBlank(ciphertext) ? ciphertext : decryptByFixKey(ciphertext);
    }

    private String replaceEncrypted(Pattern pattern, String plaintext) throws CheckException {
        StringBuffer value = new StringBuffer();
        Matcher matcher = pattern.matcher(plaintext);
        while (matcher.find()) {
            matcher.appendReplacement(value, Matcher.quoteReplacement(
                    ENCRYPT_START + encryptByFixKey(matcher.group()) + ENCRYPT_END));
        }
        matcher.appendTail(value);
        return value.toString();
    }

    private String encryptByFixKey(String plaintext) throws CheckException {
        long started = System.currentTimeMillis();
        Result<SecurityResult> result = symmetricalSecurityService.encryptByFixedKey(
                SymmetricalSecurityService.Algorithm.AES256,
                plaintext.getBytes(StandardCharsets.UTF_8));
        if (result == null || (result.isSuccess() && (result.getResult() == null
                || result.getResult().getContent() == null || result.getResult().getContent().length == 0))) {
            throw new CheckException(BaseDataErrorCode.SYSTEM_INNER_ERROR, "Invalid encryption result");
        }
        if (!result.isSuccess()) throw remoteFailure(result);
        debug("encryptByFixKey", started);
        return Base64.encodeBase64String(result.getResult().getContent());
    }

    private String decryptByFixKey(String ciphertext) throws CheckException {
        long started = System.currentTimeMillis();
        final byte[] decoded;
        try {
            decoded = Base64.decodeBase64(ciphertext);
        } catch (IllegalArgumentException error) {
            throw new CheckException(BaseDataErrorCode.SYSTEM_INNER_ERROR, "Invalid ciphertext");
        }
        Result<SecurityResult> result = symmetricalSecurityService.decryptByFixedKey(
                SymmetricalSecurityService.Algorithm.AES256, decoded);
        if (!result.isSuccess()) throw remoteFailure(result);
        String plaintext = new String(result.getResult().getContent(), StandardCharsets.UTF_8);
        debug("decryptByFixKey", started);
        return plaintext;
    }

    private void debug(String operation, long started) {
        if (LOG.isDebugEnabled()) LOG.debug("SymmetricalSecurityUtils.{} elapsedMs={}",
                operation, System.currentTimeMillis() - started);
    }

    private static CheckException remoteFailure(Result<SecurityResult> result) {
        return new CheckException(BaseDataErrorCode.SYSTEM_INNER_ERROR,
                result.getErrorCode() + "|" + result.getErrorMsg());
    }

    private static boolean isBlank(String value) {
        if (value == null || value.isEmpty()) return true;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isWhitespace(value.charAt(i))) return false;
        }
        return true;
    }

    private static String join(List<String> values, String delimiter) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(delimiter);
            result.append(value);
        }
        return result.toString();
    }
}
