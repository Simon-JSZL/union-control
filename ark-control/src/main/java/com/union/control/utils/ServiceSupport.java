package com.union.control.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class ServiceSupport {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:@-]{1,64}");

    private ServiceSupport() {}

    public static Map<String, Object> request(ObjectMapper json, String input) {
        if (input == null) throw new IllegalArgumentException("请求参数非法");
        try {
            Map<String, Object> value = json.readValue(
                    input, new TypeReference<Map<String, Object>>() {});
            if (value == null) throw new IllegalArgumentException("请求参数非法");
            return value;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("请求参数不是合法 JSON", error);
        }
    }

    public static String userId(Map<String, Object> request) {
        return identity(request, "userId");
    }

    public static String identity(Map<String, Object> request, String field) {
        String value = text(request, field, 64, true);
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException(field + " 非法");
        return value;
    }

    public static void requireId(String value) {
        if (value == null || !ID.matcher(value).matches())
            throw new IllegalArgumentException("conversationId 非法");
    }

    public static void requireExecutionToken(String value) {
        if (value == null || !ID.matcher(value).matches())
            throw new IllegalArgumentException("runId 非法");
    }

    public static int integer(Map<String, Object> values, String key, int min, int max) {
        Object raw = values.get(key);
        if (!(raw instanceof Number)) throw new IllegalArgumentException(key + " 非法");
        int value = ((Number) raw).intValue();
        requireRange(value, min, max, key);
        return value;
    }

    public static long number(Object value) {
        return value instanceof Number
                ? ((Number) value).longValue()
                : Long.parseLong(String.valueOf(value));
    }

    public static String optionalText(Map<String, Object> values, String key, int max) {
        return text(values, key, max, false);
    }

    public static String text(Map<?, ?> values, String key, int max, boolean required) {
        Object raw = values.get(key);
        if (raw != null && !(raw instanceof String))
            throw new IllegalArgumentException(key + " 非法");
        String value = raw == null ? null : ((String) raw).trim();
        if (value != null && value.isEmpty()) value = null;
        if ((required && value == null) || (value != null && value.length() > max))
            throw new IllegalArgumentException(key + " 非法");
        return value;
    }

    public static void requireRange(int value, int min, int max, String name) {
        if (value < min || value > max)
            throw new IllegalArgumentException(name + " 超出范围");
    }

    public static String likePrefix(String prefix) {
        return prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    public static boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    public static Map<String, Object> success() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        return response;
    }

    public static Map<String, Object> ok(Object data) {
        Map<String, Object> response = success();
        response.put("data", data);
        return response;
    }
}
