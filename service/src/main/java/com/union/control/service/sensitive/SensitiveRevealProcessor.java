package com.union.control.service.sensitive;

import com.nucc.channel.ark.common.annotation.EnDecryptField;
import com.nucc.channel.ark.common.annotation.EnDecryptFieldLong;
import com.nucc.channel.ark.common.annotation.EnDecryptFieldWithTag;
import com.nucc.channel.ark.common.exception.CheckException;
import com.union.control.utils.security.SymmetricalSecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.nucc.channel.ark.common.util.Constant.REGEX_EMAIL;
import static com.nucc.channel.ark.common.util.Constant.REGEX_MOBILE;
import static com.nucc.channel.ark.common.util.Constant.REGEX_TELEPHONE;

@SuppressWarnings({"rawtypes", "unchecked"})
public final class SensitiveRevealProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(SensitiveRevealProcessor.class);
    private static final Pattern SENSITIVE = Pattern.compile(
            "(" + REGEX_MOBILE + ")|(" + REGEX_EMAIL + ")|(" + REGEX_TELEPHONE + ")");
    private static final String[] MAP_KEYS = {
            "mobileNumber", "phoneNumber", "telNumber", "email"
    };

    private final SymmetricalSecurityUtils crypto;
    private final RedisRevealTokenStore tokens;
    private final SecureRandom random;

    public SensitiveRevealProcessor(SymmetricalSecurityUtils crypto, RedisRevealTokenStore tokens) {
        this(crypto, tokens, new SecureRandom());
    }

    SensitiveRevealProcessor(SymmetricalSecurityUtils crypto, RedisRevealTokenStore tokens,
                             SecureRandom random) {
        this.crypto = crypto;
        this.tokens = tokens;
        this.random = random;
    }

    public void process(Object result) {
        List<ValueTarget> targets = new ArrayList<>();
        collect(result, targets);
        apply(targets);
    }

    public String maskText(String plaintext) {
        final String[] result = {plaintext};
        List<ValueTarget> targets = new ArrayList<>();
        targets.add(new ValueTarget(plaintext) {
            @Override
            void set(String value) {
                result[0] = value;
            }
        });
        apply(targets);
        return result[0];
    }

    private void apply(List<ValueTarget> targets) {
        List<RedisRevealTokenStore.Entry> entries = new ArrayList<>();
        List<Plan> plans = new ArrayList<>(targets.size());
        for (ValueTarget target : targets) plans.add(plan(target, entries));

        boolean clickable = entries.isEmpty();
        if (!entries.isEmpty()) {
            try {
                clickable = tokens.putAll(entries);
            } catch (RuntimeException error) {
                clickable = false;
                LOG.warn("Sensitive reveal token batch unavailable match_count={}", entries.size());
            }
        }
        for (Plan plan : plans) plan.target.set(clickable ? plan.clickable : plan.masked);
    }

    private Plan plan(ValueTarget target, List<RedisRevealTokenStore.Entry> entries) {
        if (target.value == null || target.value.isEmpty()) return new Plan(target, target.value, target.value);
        Matcher matcher = SENSITIVE.matcher(target.value);
        StringBuilder masked = new StringBuilder();
        StringBuilder clickable = new StringBuilder();
        int end = 0;
        while (matcher.find()) {
            masked.append(target.value, end, matcher.start());
            clickable.append(target.value, end, matcher.start());
            String plaintext = matcher.group();
            String mask = mask(plaintext);
            masked.append("[#").append(mask).append(']');
            String token = null;
            try {
                token = token();
                entries.add(new RedisRevealTokenStore.Entry(
                        token, crypto.encryptWithCheck(plaintext)));
            } catch (CheckException error) {
                LOG.warn("Sensitive reveal fragment encryption failed error_type={}",
                        error.getClass().getSimpleName());
            }
            clickable.append("[#").append(mask);
            if (token != null) clickable.append("#VIEW:").append(token);
            clickable.append(']');
            end = matcher.end();
        }
        if (end == 0) return new Plan(target, target.value, target.value);
        masked.append(target.value, end, target.value.length());
        clickable.append(target.value, end, target.value.length());
        return new Plan(target, masked.toString(), clickable.toString());
    }

    private void collect(Object result, List<ValueTarget> targets) {
        if (result == null) return;
        if (result instanceof List<?>) {
            for (Object value : (List<?>) result) collectOne(value, targets);
        } else {
            collectOne(result, targets);
        }
    }

    private void collectOne(Object value, List<ValueTarget> targets) {
        if (value == null) return;
        if (value instanceof Map<?, ?>) {
            Map map = (Map) value;
            for (String key : MAP_KEYS) {
                if (map.containsKey(key)) addMapValue(map, key, targets);
            }
            return;
        }
        for (Class<?> type = value.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) addFieldValue(value, field, targets);
        }
    }

    private void addMapValue(final Map map, final String key, List<ValueTarget> targets) {
        Object ciphertext = map.get(key);
        if (!(ciphertext instanceof String)) return;
        try {
            targets.add(new ValueTarget(crypto.decryptWithCheckNoLog((String) ciphertext)) {
                @Override
                void set(String value) {
                    map.put(key, value);
                }
            });
        } catch (CheckException error) {
            map.put(key, "****");
            LOG.warn("Sensitive reveal field decryption failed field_kind=map error_type={}",
                    error.getClass().getSimpleName());
        }
    }

    private void addFieldValue(final Object owner, final Field field, List<ValueTarget> targets) {
        if (!field.isAnnotationPresent(EnDecryptField.class)
                && !field.isAnnotationPresent(EnDecryptFieldLong.class)
                && !field.isAnnotationPresent(EnDecryptFieldWithTag.class)) return;
        try {
            field.setAccessible(true);
            String ciphertext = (String) field.get(owner);
            final String plaintext;
            if (field.isAnnotationPresent(EnDecryptField.class)) {
                plaintext = crypto.decryptWithCheckNoLog(ciphertext);
            } else if (field.isAnnotationPresent(EnDecryptFieldLong.class)) {
                plaintext = crypto.decryptLongString(ciphertext);
            } else {
                plaintext = crypto.decryptWithTag(ciphertext);
            }
            targets.add(new ValueTarget(plaintext) {
                @Override
                void set(String value) {
                    try {
                        field.set(owner, value);
                    } catch (IllegalAccessException impossible) {
                        throw new IllegalStateException(impossible);
                    }
                }
            });
        } catch (Exception error) {
            try {
                field.set(owner, "****");
            } catch (RuntimeException | IllegalAccessException maskingError) {
                throw new SensitiveRevealProcessingException(maskingError);
            }
            LOG.warn("Sensitive reveal field decryption failed field_kind=pojo error_type={}",
                    error.getClass().getSimpleName());
        }
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return "rt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String mask(String value) {
        int at = value.indexOf('@');
        if (at > 0) {
            return value.substring(0, 1) + "***" + value.substring(at);
        }
        String digits = value.replace("-", "");
        if (digits.length() == 11 && digits.charAt(0) == '1') {
            return digits.substring(0, 3) + "****" + digits.substring(7);
        }
        if (digits.length() > 7) {
            return digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4);
        }
        return "****";
    }

    private abstract static class ValueTarget {
        final String value;

        ValueTarget(String value) {
            this.value = value;
        }

        abstract void set(String value);
    }

    private static final class Plan {
        final ValueTarget target;
        final String masked;
        final String clickable;

        Plan(ValueTarget target, String masked, String clickable) {
            this.target = target;
            this.masked = masked;
            this.clickable = clickable;
        }
    }

    public static final class SensitiveRevealProcessingException extends RuntimeException {
        SensitiveRevealProcessingException(Throwable cause) {
            super("Sensitive reveal processing failed", cause);
        }
    }
}
