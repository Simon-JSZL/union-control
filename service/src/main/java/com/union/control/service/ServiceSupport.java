package com.union.control.service;

import com.epcc.arkweb.helper.AuthContextHolder;
import com.epcc.arkweb.model.ShiroUser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static com.union.control.service.ServiceExceptions.UnauthorizedException;

final class ServiceSupport {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:@-]{1,64}");

    private ServiceSupport() {}

    static ShiroUser currentUser() {
        ShiroUser user = AuthContextHolder.getAuthUserDetails();
        if (user == null || user.getLoginName() == null || user.getLoginName().trim().isEmpty())
            throw new UnauthorizedException();
        return user;
    }

    static String currentUserId() {
        return currentUser().getLoginName();
    }

    static void requireId(String value) {
        if (value == null || !ID.matcher(value).matches())
            throw new IllegalArgumentException("conversationId 非法");
    }

    static void requireExecutionToken(String value) {
        if (value == null || !ID.matcher(value).matches())
            throw new IllegalArgumentException("runId 非法");
    }

    static int integer(Map<String, Object> values, String key, int min, int max) {
        Object raw = values.get(key);
        if (!(raw instanceof Number)) throw new IllegalArgumentException(key + " 非法");
        int value = ((Number) raw).intValue();
        requireRange(value, min, max, key);
        return value;
    }

    static long number(Object value) {
        return value instanceof Number
                ? ((Number) value).longValue()
                : Long.parseLong(String.valueOf(value));
    }

    static String optionalText(Map<String, Object> values, String key, int max) {
        return text(values, key, max, false);
    }

    static String text(Map<?, ?> values, String key, int max, boolean required) {
        Object raw = values.get(key);
        if (raw != null && !(raw instanceof String))
            throw new IllegalArgumentException(key + " 非法");
        String value = raw == null ? null : ((String) raw).trim();
        if (value != null && value.isEmpty()) value = null;
        if ((required && value == null) || (value != null && value.length() > max))
            throw new IllegalArgumentException(key + " 非法");
        return value;
    }

    static void requireRange(int value, int min, int max, String name) {
        if (value < min || value > max)
            throw new IllegalArgumentException(name + " 超出范围");
    }

    static String likePrefix(String prefix) {
        return prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    static boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    static Map<String, Object> success() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        return response;
    }

    static Map<String, Object> ok(Object data) {
        Map<String, Object> response = success();
        response.put("data", data);
        return response;
    }
}
