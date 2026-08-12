package com.union.control.sensitive.reveal;

import com.union.control.sensitive.interceptor.DecryptedValueHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MaskedRevealHook implements DecryptedValueHook {
    private static final Logger LOG = LoggerFactory.getLogger(MaskedRevealHook.class);
    private static final Pattern MOBILE_PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    private final RevealTokenStore tokens;
    private final SecureRandom random = new SecureRandom();

    public MaskedRevealHook(RevealTokenStore tokens) {
        this.tokens = tokens;
    }

    @Override
    public List<String> afterDecrypt(String userId, List<DecryptedValueHook.Value> values) {
        List<String> masks = new ArrayList<>(values.size());
        List<String> revealTokens = new ArrayList<>(values.size());
        List<RevealTokenStore.Entry> entries = new ArrayList<>(values.size());
        for (DecryptedValueHook.Value value : values) {
            String token = newToken();
            masks.add(mask(value.getPlaintext()));
            revealTokens.add(token);
            entries.add(new RevealTokenStore.Entry(token, value.getCiphertext()));
        }
        boolean clickable;
        try {
            clickable = tokens.putAll(userId, entries);
        } catch (StoreUnavailableException error) {
            LOG.warn("Sensitive reveal token store unavailable; returning masked-only value");
            clickable = false;
        }
        List<String> result = new ArrayList<>(values.size());
        for (int i = 0; i < masks.size(); i++)
            result.add("[#" + masks.get(i) + (clickable ? "#VIEW:" + revealTokens.get(i) : "") + "]");
        return result;
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return "rt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String mask(String plaintext) {
        Matcher phones = MOBILE_PHONE.matcher(plaintext);
        StringBuffer result = new StringBuffer();
        boolean found = false;
        while (phones.find()) {
            String phone = phones.group();
            phones.appendReplacement(result, phone.substring(0, 3) + "*****" + phone.substring(8));
            found = true;
        }
        if (found) {
            phones.appendTail(result);
            return result.toString();
        }
        int length = plaintext.length();
        if (length <= 2) return stars(length);
        if (length <= 8) return plaintext.substring(0, 1) + "*****" + plaintext.substring(length - 1);
        return plaintext.substring(0, 4) + "*****" + plaintext.substring(length - 4);
    }

    private static String stars(int length) {
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) value.append('*');
        return value.toString();
    }
}
