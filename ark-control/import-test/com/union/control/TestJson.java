package com.union.control;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TestJson {
    public static final String USER_ID = "local-user";
    public static final String ORG_CODE = "local-org";
    public static final String ROLE_ID = "local-role";
    private static final ObjectMapper JSON = new ObjectMapper();

    private TestJson() {}

    public static String request(String raw) {
        try {
            Map<String, Object> parsed = JSON.readValue(
                    raw, new TypeReference<Map<String, Object>>() {});
            return request(parsed);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    public static String request(Map<String, Object> values) {
        Map<String, Object> input = new LinkedHashMap<>(values);
        input.put("userId", USER_ID);
        input.put("orgCode", ORG_CODE);
        input.put("roleId", ROLE_ID);
        try {
            return JSON.writeValueAsString(input);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    public static String request(Object... entries) {
        Map<String, Object> input = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2)
            input.put(String.valueOf(entries[index]), entries[index + 1]);
        return request(input);
    }
}
