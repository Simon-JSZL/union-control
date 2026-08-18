package com.epcc.arkweb.helper;

import com.epcc.arkweb.model.ShiroUser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shiro.authz.UnauthenticatedException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Converts an authenticated Web request into the JSON-only service contract. */
@Component
public class AuthenticatedRequest {
    private final ObjectMapper json;

    public AuthenticatedRequest(ObjectMapper json) {
        this.json = json;
    }

    public String json() {
        return json(new LinkedHashMap<String, Object>());
    }

    public String json(Map<String, ?> payload) {
        Map<String, Object> input = new LinkedHashMap<>();
        if (payload != null) input.putAll(payload);
        ShiroUser actor = actor();
        input.put("userId", actor.getLoginName());
        input.put("orgCode", actor.getOrgCode());
        input.put("roleId", actor.getRoleId());
        return write(input);
    }

    public String json(Object... entries) {
        if (entries.length % 2 != 0) throw new IllegalArgumentException("请求参数非法");
        Map<String, Object> input = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2)
            input.put(String.valueOf(entries[index]), entries[index + 1]);
        return json(input);
    }

    public String json(byte[] payload) {
        try {
            Map<String, Object> input = json.readValue(
                    payload, new TypeReference<Map<String, Object>>() {});
            return json(input);
        } catch (Exception error) {
            throw new IllegalArgumentException("请求参数不是合法 JSON", error);
        }
    }

    public byte[] clientPayload(byte[] payload) {
        try {
            Map<String, Object> input = json.readValue(
                    payload, new TypeReference<Map<String, Object>>() {});
            input.remove("userId");
            input.remove("orgCode");
            input.remove("roleId");
            return json.writeValueAsBytes(input);
        } catch (Exception error) {
            throw new IllegalArgumentException("请求参数不是合法 JSON", error);
        }
    }

    public ShiroUser actor() {
        ShiroUser actor = AuthContextHolder.getAuthUserDetails();
        if (actor == null || blank(actor.getLoginName())) throw new UnauthenticatedException();
        return actor;
    }

    private String write(Map<String, Object> input) {
        try {
            return json.writeValueAsString(input);
        } catch (Exception error) {
            throw new IllegalArgumentException("请求参数无法序列化", error);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty() || value.length() > 64;
    }
}
