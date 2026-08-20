package com.union.control.service.sensitive;

import com.nucc.channel.ark.common.annotation.EnDecryptField;
import com.nucc.channel.ark.common.annotation.EnDecryptFieldLong;
import com.nucc.channel.ark.common.annotation.EnDecryptFieldWithTag;
import com.nucc.channel.ark.common.exception.CheckException;
import com.union.control.utils.security.SymmetricalSecurityUtils;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Traverses supported MyBatis parameter/result shapes and transforms annotated strings. */
public final class SensitiveFieldCodec {
    private static final Set<String> MAP_KEYS = unmodifiableKeys();

    private final SymmetricalSecurityUtils crypto;

    public SensitiveFieldCodec(SymmetricalSecurityUtils crypto) {
        if (crypto == null) throw new IllegalArgumentException("Crypto service is required");
        this.crypto = crypto;
    }

    public void encrypt(Object value) throws CheckException {
        transform(value, true, Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()));
    }

    public void decrypt(Object value) throws CheckException {
        transform(value, false, Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void transform(Object value, boolean encrypt, Set<Object> visited) throws CheckException {
        if (value == null || isSimple(value.getClass()) || !visited.add(value)) return;

        if (value instanceof Map<?, ?>) {
            Map map = (Map) value;
            IdentityHashMap<Object, Object> replacements = new IdentityHashMap<>();
            for (Object entryObject : map.entrySet()) {
                Map.Entry entry = (Map.Entry) entryObject;
                Object child = entry.getValue();
                if (entry.getKey() instanceof String && MAP_KEYS.contains(entry.getKey())
                        && child instanceof String) {
                    Object transformed = transformWhole((String) child, encrypt);
                    entry.setValue(transformed);
                    replacements.put(child, transformed);
                }
            }
            for (Object entryObject : map.entrySet()) {
                Map.Entry entry = (Map.Entry) entryObject;
                Object child = entry.getValue();
                if (replacements.containsKey(child)) {
                    entry.setValue(replacements.get(child));
                } else {
                    transform(child, encrypt, visited);
                }
            }
            return;
        }

        if (value instanceof Iterable<?>) {
            for (Object child : (Iterable<?>) value) transform(child, encrypt, visited);
            return;
        }

        if (value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                transform(Array.get(value, i), encrypt, visited);
            }
            return;
        }

        for (Class<?> type = value.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) transformField(value, field, encrypt);
        }
    }

    private void transformField(Object owner, Field field, boolean encrypt) throws CheckException {
        if (!isSensitive(field)) return;
        if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())
                || field.getType() != String.class) {
            throw new IllegalStateException("Sensitive field must be a writable String: "
                    + field.getDeclaringClass().getName() + "." + field.getName());
        }

        boolean accessible = field.isAccessible();
        try {
            field.setAccessible(true);
            String value = (String) field.get(owner);
            field.set(owner, transformFieldValue(field, value, encrypt));
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
            return transformWhole(value, encrypt);
        }
        if (field.isAnnotationPresent(EnDecryptFieldWithTag.class)) {
            return encrypt ? crypto.encryptWithTag(value) : crypto.decryptWithTag(value);
        }
        EnDecryptFieldLong longField = field.getAnnotation(EnDecryptFieldLong.class);
        return encrypt ? crypto.encryptLongString(value, longField.chunkSize())
                : crypto.decryptLongString(value);
    }

    private String transformWhole(String value, boolean encrypt) throws CheckException {
        return encrypt ? crypto.encryptWithCheck(value) : crypto.decryptWithCheckNoLog(value);
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

    private static Set<String> unmodifiableKeys() {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        keys.add("mobileNumber");
        keys.add("phoneNumber");
        keys.add("telNumber");
        keys.add("email");
        return Collections.unmodifiableSet(keys);
    }
}
