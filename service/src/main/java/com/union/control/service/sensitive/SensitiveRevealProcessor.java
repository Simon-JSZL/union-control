package com.union.control.service.sensitive;

import com.nucc.channel.ark.common.annotation.EnDecryptField;
import com.nucc.channel.ark.common.annotation.EnDecryptFieldLong;
import com.nucc.channel.ark.common.annotation.EnDecryptFieldWithTag;
import com.nucc.channel.ark.common.exception.CheckException;
import com.union.control.utils.security.SymmetricalSecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.nucc.channel.ark.common.util.Constant.REGEX_EMAIL;
import static com.nucc.channel.ark.common.util.Constant.REGEX_MOBILE;
import static com.nucc.channel.ark.common.util.Constant.REGEX_TELEPHONE;

@SuppressWarnings({"rawtypes", "unchecked"})
@Component
public final class SensitiveRevealProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(SensitiveRevealProcessor.class);
    private static final Pattern SENSITIVE = Pattern.compile(
            "(" + REGEX_MOBILE + ")|(" + REGEX_EMAIL + ")|(" + REGEX_TELEPHONE + ")");
    private static final Set<String> MAP_KEYS = new HashSet<>(Arrays.asList(
            "mobileNumber", "phoneNumber", "telNumber", "email"));

    private final SymmetricalSecurityUtils crypto;
    private final RedisRevealTokenStore tokens;
    private final SecureRandom random;

    @Autowired
    public SensitiveRevealProcessor(SymmetricalSecurityUtils crypto, RedisRevealTokenStore tokens) {
        this(crypto, tokens, new SecureRandom());
    }

    public void encrypt(Object value) throws CheckException {
        transform(value, true, null, visited());
    }

    public void decrypt(Object value) throws CheckException {
        transform(value, false, null, visited());
    }

    SensitiveRevealProcessor(SymmetricalSecurityUtils crypto, RedisRevealTokenStore tokens,
                             SecureRandom random) {
        this.crypto = crypto;
        this.tokens = tokens;
        this.random = random;
    }

    public void process(Object result) {
        List<ValueTarget> targets = new ArrayList<>();
        try {
            transform(result, false, targets, visited());
        } catch (CheckException impossible) {
            throw new IllegalStateException(impossible);
        }
        apply(targets);
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
            String revealToken = null;
            try {
                String token = token();
                entries.add(new RedisRevealTokenStore.Entry(
                        token, crypto.encryptWithCheck(plaintext)));
                revealToken = token;
            } catch (CheckException error) {
                LOG.warn("Sensitive reveal fragment encryption failed error_type={}",
                        error.getClass().getSimpleName());
            }
            clickable.append("[#").append(mask);
            if (revealToken != null) clickable.append("#VIEW:").append(revealToken);
            clickable.append(']');
            end = matcher.end();
        }
        if (end == 0) return new Plan(target, target.value, target.value);
        masked.append(target.value, end, target.value.length());
        clickable.append(target.value, end, target.value.length());
        return new Plan(target, masked.toString(), clickable.toString());
    }

    private void addMapValue(final Map map, final Object key, String ciphertext,
                             List<ValueTarget> targets) {
        try {
            targets.add(new ValueTarget(crypto.decryptWithCheckNoLog(ciphertext),
                    value -> map.put(key, value)));
        } catch (CheckException error) {
            map.put(key, "****");
            LOG.warn("Sensitive reveal field decryption failed field_kind=map error_type={}",
                    error.getClass().getSimpleName());
        }
    }

    private void addFieldValue(final Object owner, final Field field, List<ValueTarget> targets) {
        validateField(field);
        try {
            String ciphertext = readField(owner, field);
            final String plaintext = transformFieldValue(field, ciphertext, false);
            targets.add(new ValueTarget(plaintext, value -> writeField(owner, field, value)));
        } catch (CheckException | RuntimeException error) {
            writeField(owner, field, "****");
            LOG.warn("Sensitive reveal field decryption failed field_kind=pojo error_type={}",
                    error.getClass().getSimpleName());
        }
    }

    private void transform(Object value, boolean encrypt, List<ValueTarget> revealTargets,
                           Set<Object> visited) throws CheckException {
        if (value == null || isSimple(value.getClass()) || !visited.add(value)) return;
        if (value instanceof Map<?, ?>) {
            Map map = (Map) value;
            IdentityHashMap<Object, Object> replacements = revealTargets == null
                    ? new IdentityHashMap<>() : null;
            for (Object item : map.entrySet()) {
                Map.Entry entry = (Map.Entry) item;
                Object child = entry.getValue();
                if (entry.getKey() instanceof String && MAP_KEYS.contains(entry.getKey())
                        && child instanceof String) {
                    if (revealTargets == null) {
                        replacements.put(child, encrypt ? crypto.encryptWithCheck((String) child)
                                : crypto.decryptWithCheckNoLog((String) child));
                    } else {
                        addMapValue(map, entry.getKey(), (String) child, revealTargets);
                    }
                }
            }
            for (Object item : map.entrySet()) {
                Map.Entry entry = (Map.Entry) item;
                Object child = entry.getValue();
                if (replacements != null && replacements.containsKey(child)) {
                    entry.setValue(replacements.get(child));
                } else if (!(entry.getKey() instanceof String && MAP_KEYS.contains(entry.getKey())
                        && child instanceof String)) {
                    transform(child, encrypt, revealTargets, visited);
                }
            }
            return;
        }
        if (value instanceof Iterable<?>) {
            for (Object child : (Iterable<?>) value) {
                transform(child, encrypt, revealTargets, visited);
            }
            return;
        }
        if (value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                transform(Array.get(value, i), encrypt, revealTargets, visited);
            }
            return;
        }
        for (Class<?> type = value.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!isSensitive(field)) continue;
                if (revealTargets == null) transformField(value, field, encrypt);
                else addFieldValue(value, field, revealTargets);
            }
        }
    }

    private void transformField(Object owner, Field field, boolean encrypt) throws CheckException {
        validateField(field);
        writeField(owner, field, transformFieldValue(field, readField(owner, field), encrypt));
    }

    private static void validateField(Field field) {
        if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())
                || field.getType() != String.class) {
            throw new IllegalStateException("Sensitive field must be a writable String: "
                    + field.getDeclaringClass().getName() + "." + field.getName());
        }
    }

    private static String readField(Object owner, Field field) {
        boolean accessible = field.isAccessible();
        try {
            field.setAccessible(true);
            return (String) field.get(owner);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Sensitive field is not accessible", error);
        } finally {
            field.setAccessible(accessible);
        }
    }

    private static void writeField(Object owner, Field field, String value) {
        boolean accessible = field.isAccessible();
        try {
            field.setAccessible(true);
            field.set(owner, value);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Sensitive field is not accessible", error);
        } finally {
            field.setAccessible(accessible);
        }
    }

    private String transformFieldValue(Field field, String value, boolean encrypt)
            throws CheckException {
        if (value == null) return null;
        if (field.isAnnotationPresent(EnDecryptField.class)) {
            return encrypt ? crypto.encryptWithCheck(value) : crypto.decryptWithCheckNoLog(value);
        }
        if (field.isAnnotationPresent(EnDecryptFieldWithTag.class)) {
            return encrypt ? crypto.encryptWithTag(value) : crypto.decryptWithTag(value);
        }
        EnDecryptFieldLong longField = field.getAnnotation(EnDecryptFieldLong.class);
        return encrypt ? crypto.encryptLongString(value, longField.chunkSize())
                : crypto.decryptLongString(value);
    }

    private static boolean isSensitive(Field field) {
        return field.isAnnotationPresent(EnDecryptField.class)
                || field.isAnnotationPresent(EnDecryptFieldLong.class)
                || field.isAnnotationPresent(EnDecryptFieldWithTag.class);
    }

    private static boolean isSimple(Class<?> type) {
        return type.isPrimitive() || CharSequence.class.isAssignableFrom(type)
                || Number.class.isAssignableFrom(type) || Boolean.class == type
                || Character.class == type || Enum.class.isAssignableFrom(type);
    }

    private static Set<Object> visited() {
        return Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
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

    private static final class ValueTarget {
        final String value;
        final Consumer<String> setter;

        ValueTarget(String value, Consumer<String> setter) {
            this.value = value;
            this.setter = setter;
        }

        void set(String value) {
            setter.accept(value);
        }
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
}
