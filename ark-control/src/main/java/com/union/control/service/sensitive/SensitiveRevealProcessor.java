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
import org.apache.ibatis.binding.MapperMethod;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
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
            "(" + REGEX_EMAIL + ")|(" + REGEX_MOBILE + ")|(" + REGEX_TELEPHONE + ")");
    // Match only the display shapes produced by mask(), not arbitrary marker prefixes.
    private static final String MASK_FORMAT =
            "(?:[0-9]{3}\\*{4}[0-9]{4}|[a-zA-Z0-9._%+-]\\*{3}@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}|\\*{4})";
    private static final Pattern MARKER = Pattern.compile(
            "\\[#(" + MASK_FORMAT + ")#VIEW:(rt_[A-Za-z0-9_-]{43})\\]");
    private static final Pattern UNRESOLVED_MARKER = Pattern.compile(
            "\\[#" + MASK_FORMAT + "(?=[\\]#\\r\\n]|$)");
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
        encryptForExecution(value);
    }

    /** Apply only after every value is ready; the executor restores caller inputs after binding. */
    public Runnable encryptForExecution(Object value) throws CheckException {
        if (value != null && isSimple(value.getClass())) {
            throw new IllegalArgumentException("Sensitive SQL requires named parameters or an annotated row");
        }
        return encryptQueryParameters(value);
    }

    /** AddressBook queries may also receive a scalar ID, which is not a sensitive field. */
    public Runnable encryptQueryParameters(Object value) throws CheckException {
        List<WriteChange> changes = new ArrayList<>();
        collectWrites(value, changes, visited());
        int applied = 0;
        try {
            for (WriteChange change : changes) {
                change.target.set(change.ciphertext);
                applied++;
            }
        } catch (RuntimeException error) {
            try { restoreWrites(changes, applied); }
            catch (RuntimeException restoreError) { error.addSuppressed(restoreError); }
            throw error;
        }
        return () -> restoreWrites(changes, changes.size());
    }

    private static void restoreWrites(List<WriteChange> changes, int count) {
        RuntimeException failure = null;
        for (int i = count - 1; i >= 0; i--) {
            try { changes.get(i).restore(); }
            catch (RuntimeException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    private void collectWrites(Object value, List<WriteChange> changes, Set<Object> visited)
            throws CheckException {
        if (value == null || isSimple(value.getClass()) || !visited.add(value)) return;
        if (value instanceof Iterable<?>) {
            for (Object child : (Iterable<?>) value) collectWrites(child, changes, visited);
        } else if (value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                collectWrites(Array.get(value, i), changes, visited);
            }
        } else if (value instanceof Map<?, ?>) {
            Map map = (Map) value;
            IdentityHashMap<Object, String> replacements = new IdentityHashMap<>();
            for (Object item : map.entrySet()) {
                Map.Entry entry = (Map.Entry) item;
                Object key = entry.getKey();
                Object child = entry.getValue();
                if (MAP_KEYS.contains(key) && child != null) {
                    if (!(child instanceof String)) throw invalidWrite();
                    String plaintext = restoreForWrite((String) child);
                    String ciphertext = checkedCipher(plaintext, crypto.encryptWithCheck(plaintext), false);
                    replacements.put(child, ciphertext);
                    changes.add(new WriteChange(new ValueTarget((String) child,
                            replacement -> map.put(key, replacement)), ciphertext));
                } else {
                    collectWrites(child, changes, visited);
                }
            }
            // Only MyBatis's generic aliases represent the same SQL argument.
            if (map instanceof MapperMethod.ParamMap<?>) {
                for (Object item : map.entrySet()) {
                    Map.Entry entry = (Map.Entry) item;
                    Object key = entry.getKey();
                    Object child = entry.getValue();
                    if (replacements.containsKey(child) && !MAP_KEYS.contains(key)) {
                        if (!(key instanceof String) || !((String) key).matches("param[1-9][0-9]*")) {
                            throw new IllegalArgumentException("Ambiguous sensitive MyBatis parameter alias");
                        }
                        changes.add(new WriteChange(new ValueTarget((String) child,
                                replacement -> map.put(key, replacement)), replacements.get(child)));
                    }
                }
            }
        } else {
            for (Class<?> type = value.getClass(); type != null && type != Object.class;
                 type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (!isSensitive(field)) continue;
                    validateField(field);
                    String original = readField(value, field);
                    changes.add(new WriteChange(new ValueTarget(original,
                            replacement -> writeField(value, field, replacement)),
                            transformFieldValue(field, original, true)));
                }
            }
        }
    }

    private static String checkedCipher(String plaintext, String ciphertext, boolean tagged) {
        if (plaintext != null && !plaintext.trim().isEmpty()
                && (ciphertext == null || ciphertext.isEmpty()
                || (plaintext.equals(ciphertext) && (!tagged || SENSITIVE.matcher(plaintext).find())))) {
            throw new IllegalStateException("Sensitive encryption returned an invalid result");
        }
        return ciphertext;
    }

    public void decrypt(Object value) throws CheckException {
        transform(value, null, visited(), new ReadContext(), row -> true);
    }

    SensitiveRevealProcessor(SymmetricalSecurityUtils crypto, RedisRevealTokenStore tokens,
                             SecureRandom random) {
        this.crypto = crypto;
        this.tokens = tokens;
        this.random = random;
    }

    public void process(Object result) {
        process(result, row -> false);
    }

    /** One read context for the entire result, including rows allowed to display plaintext. */
    public void process(Object result, Predicate<Object> plaintextRows) {
        List<ValueTarget> targets = new ArrayList<>();
        ReadContext reads = new ReadContext();
        try {
            transform(result, targets, visited(), reads, plaintextRows);
        } catch (CheckException error) {
            throw new IllegalStateException("Sensitive plaintext row decryption failed", error);
        }
        apply(targets, reads);
    }

    private void apply(List<ValueTarget> targets, ReadContext reads) {
        List<RedisRevealTokenStore.Entry> entries = new ArrayList<>();
        List<Plan> plans = new ArrayList<>(targets.size());
        for (ValueTarget target : targets) plans.add(plan(target, entries, reads));

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

    private Plan plan(ValueTarget target, List<RedisRevealTokenStore.Entry> entries, ReadContext reads) {
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
            clickable.append(reads.marker(plaintext, entries));
            end = matcher.end();
        }
        if (end == 0) return new Plan(target, target.value, target.value);
        masked.append(target.value, end, target.value.length());
        clickable.append(target.value, end, target.value.length());
        return new Plan(target, masked.toString(), clickable.toString());
    }

    private void addMapValue(final Map map, final Object key, String ciphertext,
                             List<ValueTarget> targets, ReadContext reads) {
        try {
            targets.add(new ValueTarget(reads.decrypt(null, ciphertext),
                    value -> map.put(key, value)));
        } catch (CheckException | RuntimeException error) {
            map.put(key, "[#****]");
        }
    }

    private void addFieldValue(final Object owner, final Field field, List<ValueTarget> targets, ReadContext reads) {
        validateField(field);
        try {
            String ciphertext = readField(owner, field);
            final String plaintext = reads.decrypt(field, ciphertext);
            targets.add(new ValueTarget(plaintext, value -> writeField(owner, field, value)));
        } catch (CheckException | RuntimeException error) {
            writeField(owner, field, "[#****]");
        }
    }

    private void transform(Object value, List<ValueTarget> revealTargets, Set<Object> visited,
                           ReadContext reads, Predicate<Object> plaintextRows) throws CheckException {
        if (value == null) return;
        // ponytail: business inputs are a single row or one container of rows; no nested traversal.
        if (value instanceof Iterable<?>) {
            for (Object row : (Iterable<?>) value) {
                transformRow(row, revealTargets, visited, reads, plaintextRows);
            }
        } else if (value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                transformRow(Array.get(value, i), revealTargets, visited, reads, plaintextRows);
            }
        } else {
            transformRow(value, revealTargets, visited, reads, plaintextRows);
        }
    }

    private void transformRow(Object value, List<ValueTarget> revealTargets, Set<Object> visited,
                              ReadContext reads, Predicate<Object> plaintextRows) throws CheckException {
        if (value == null || isSimple(value.getClass()) || !visited.add(value)) return;
        if (plaintextRows.test(value)) revealTargets = null;
        if (value instanceof Map<?, ?>) {
            Map map = (Map) value;
            for (Object item : map.entrySet()) {
                Map.Entry entry = (Map.Entry) item;
                Object child = entry.getValue();
                if (MAP_KEYS.contains(entry.getKey()) && child instanceof String) {
                    if (revealTargets == null) {
                        entry.setValue(reads.decrypt(null, (String) child));
                    } else {
                        addMapValue(map, entry.getKey(), (String) child, revealTargets, reads);
                    }
                }
            }
            return;
        }
        // Fields are obtained locally; accessibility changes are not shared with callers.
        for (Class<?> type = value.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!isSensitive(field)) continue;
                validateField(field);
                if (revealTargets == null) writeField(value, field, reads.decrypt(field, readField(value, field)));
                else addFieldValue(value, field, revealTargets, reads);
            }
        }
    }

    private static void validateField(Field field) {
        if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())
                || field.getType() != String.class) {
            throw new IllegalStateException("Sensitive field must be a writable String: "
                    + field.getDeclaringClass().getName() + "." + field.getName());
        }
    }

    private static String readField(Object owner, Field field) {
        try {
            field.setAccessible(true);
            return (String) field.get(owner);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Sensitive field is not accessible", error);
        }
    }

    private static void writeField(Object owner, Field field, String value) {
        try {
            field.setAccessible(true);
            field.set(owner, value);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Sensitive field is not accessible", error);
        }
    }

    private String transformFieldValue(Field field, String value, boolean encrypt)
            throws CheckException {
        if (value == null) return null;
        if (encrypt) value = restoreForWrite(value);
        if (field.isAnnotationPresent(EnDecryptField.class)) {
            return encrypt ? checkedCipher(value, crypto.encryptWithCheck(value), false)
                    : crypto.decryptWithCheckNoLog(value);
        }
        if (field.isAnnotationPresent(EnDecryptFieldWithTag.class)) {
            return encrypt ? checkedCipher(value, crypto.encryptWithTag(value), true) : crypto.decryptWithTag(value);
        }
        EnDecryptFieldLong longField = field.getAnnotation(EnDecryptFieldLong.class);
        return encrypt ? checkedCipher(value, crypto.encryptLongString(value, longField.chunkSize()), false)
                : crypto.decryptLongString(value);
    }

    /** Resolve display markers before any storage codec; never persist a failed reveal. */
    private String restoreForWrite(String value) {
        Matcher matcher = MARKER.matcher(value);
        StringBuffer restored = new StringBuffer();
        while (matcher.find()) {
            final String plaintext;
            try {
                String ciphertext = tokens.get(matcher.group(2));
                if (ciphertext == null || ciphertext.isEmpty()) throw invalidWrite();
                plaintext = crypto.decryptWithCheckNoLog(ciphertext);
                if (plaintext == null || !SENSITIVE.matcher(plaintext).matches()
                        || !mask(plaintext).equals(matcher.group(1))) throw invalidWrite();
            } catch (CheckException | RuntimeException error) {
                // Do not expose the token, ciphertext, plaintext, or provider error.
                throw invalidWrite();
            }
            matcher.appendReplacement(restored, Matcher.quoteReplacement(plaintext));
        }
        matcher.appendTail(restored);
        String plaintext = restored.toString();
        if (UNRESOLVED_MARKER.matcher(plaintext).find()) throw invalidWrite();
        return plaintext;
    }

    private static IllegalArgumentException invalidWrite() {
        return new IllegalArgumentException("敏感字段脱敏标记无效、已过期或还原失败，请刷新后重新编辑");
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

    /** Request-local only: never retain plaintext, tokens or failures between queries. */
    private final class ReadContext {
        final Map<String, String> plaintexts = new HashMap<>();
        final Map<String, Exception> failures = new HashMap<>();
        final Map<String, String> fragmentCiphertexts = new HashMap<>();
        final Map<String, String> markers = new HashMap<>();

        String decrypt(Field field, String ciphertext) throws CheckException {
            if (ciphertext == null) return null;
            String codec = field == null || field.isAnnotationPresent(EnDecryptField.class) ? "normal"
                    : field.isAnnotationPresent(EnDecryptFieldWithTag.class) ? "tagged" : "long";
            String key = codec + ":" + ciphertext;
            Exception failure = failures.get(key);
            if (failure instanceof CheckException) throw (CheckException) failure;
            if (failure != null) throw (RuntimeException) failure;
            if (plaintexts.containsKey(key)) return plaintexts.get(key);
            try {
                String plaintext = field == null ? crypto.decryptWithCheckNoLog(ciphertext)
                        : transformFieldValue(field, ciphertext, false);
                plaintexts.put(key, plaintext);
                // Only a complete normal field has exactly the reveal endpoint's storage codec.
                // Tagged text and chunked ciphertext must never be stored as a fragment token.
                if ("normal".equals(codec) && plaintext != null && SENSITIVE.matcher(plaintext).matches()) {
                    fragmentCiphertexts.putIfAbsent(plaintext, ciphertext);
                }
                return plaintext;
            } catch (CheckException | RuntimeException error) {
                failures.put(key, error);
                LOG.warn("Sensitive field decryption failed codec={} error_type={}", codec,
                        error.getClass().getSimpleName());
                throw error;
            }
        }

        String marker(String plaintext, List<RedisRevealTokenStore.Entry> entries) {
            if (markers.containsKey(plaintext)) return markers.get(plaintext);
            String masked = "[#" + mask(plaintext) + "]";
            String marker = masked;
            try {
                String ciphertext = fragmentCiphertexts.get(plaintext);
                if (ciphertext == null) {
                    ciphertext = checkedCipher(plaintext, crypto.encryptWithCheck(plaintext), false);
                }
                String token = token();
                entries.add(new RedisRevealTokenStore.Entry(token, ciphertext));
                marker = "[#" + mask(plaintext) + "#VIEW:" + token + "]";
            } catch (CheckException | RuntimeException error) {
                LOG.warn("Sensitive reveal fragment encryption failed error_type={}",
                        error.getClass().getSimpleName());
            }
            markers.put(plaintext, marker);
            return marker;
        }
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

    private static final class WriteChange {
        final ValueTarget target;
        final String ciphertext;

        WriteChange(ValueTarget target, String ciphertext) {
            this.target = target;
            this.ciphertext = ciphertext;
        }

        void restore() { target.set(target.value); }
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
