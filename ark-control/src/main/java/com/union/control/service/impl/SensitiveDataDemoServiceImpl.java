package com.union.control.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.mapper.SensitiveAddressBookDemo;
import com.union.control.mapper.SensitiveDataDemoMapper;
import com.union.control.service.SensitiveDataDemoService;
import com.union.control.utils.AgentSupport;
import com.union.control.service.sensitive.SensitiveRevealService;
import org.springframework.transaction.annotation.Transactional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service("sensitiveDataDemoService")
public class SensitiveDataDemoServiceImpl implements SensitiveDataDemoService {
    private final SensitiveDataDemoMapper mapper;
    private final ObjectMapper json;
    private final SensitiveRevealService reveal;
    private static final Pattern MARKER = Pattern.compile("\\[#([^\\]\\r\\n]*?)#VIEW:(rt_[A-Za-z0-9_-]{43})\\]");

    public SensitiveDataDemoServiceImpl(SensitiveDataDemoMapper mapper, ObjectMapper json, SensitiveRevealService reveal) {
        this.mapper = mapper;
        this.json = json;
        this.reveal = reveal;
    }

    public int insert(String input) {
        Map<String, Object> payload = AgentSupport.request(json, input);
        return mapper.insert(AgentSupport.text(payload, "phoneNumber", 64, true));
    }

    public List<Map<String, Object>> query(String input) {
        AgentSupport.request(json, input);
        return mapper.query();
    }

    public int insertAddressBook(String input) {
        Map<String, Object> payload = AgentSupport.request(json, input);
        SensitiveAddressBookDemo row = new SensitiveAddressBookDemo();
        row.setName(AgentSupport.text(payload, "name", 64, true));
        row.setRole(AgentSupport.text(payload, "role", 32, true));
        row.setEmail(AgentSupport.text(payload, "email", 255, true));
        row.setTelephone(AgentSupport.text(payload, "telephone", 32, true));
        row.setMobileNumber(AgentSupport.text(payload, "mobileNumber", 32, true));
        return mapper.insertAddressBook(row);
    }

    public List<Map<String, Object>> queryAddressBook(String input) {
        AgentSupport.request(json, input);
        List<Map<String, Object>> result = new ArrayList<>();
        for (SensitiveAddressBookDemo row : mapper.queryAddressBook()) {
            result.add(addressBookValue(row));
        }
        return result;
    }

    @Transactional
    public Map<String, Object> save(String input) {
        Map<String, Object> payload = AgentSupport.request(json, input);
        AgentSupport.userId(payload);
        Long id = id(payload);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("phoneNumber", editableText(payload, "phoneNumber", 64));
        if (id == null) {
            mapper.insertSaved(row);
            id = AgentSupport.number(row.get("id"));
        } else {
            row.put("id", id);
            if (mapper.update(row) != 1) throw new IllegalArgumentException("记录不存在");
        }
        Map<String, Object> saved = mapper.queryById(id);
        if (saved == null) throw new IllegalStateException("保存结果不存在");
        return saved;
    }

    @Transactional
    public Map<String, Object> saveAddressBook(String input) {
        Map<String, Object> payload = AgentSupport.request(json, input);
        AgentSupport.userId(payload);
        SensitiveAddressBookDemo row = new SensitiveAddressBookDemo();
        row.setId(id(payload));
        row.setName(AgentSupport.text(payload, "name", 64, true));
        row.setRole(AgentSupport.text(payload, "role", 32, true));
        row.setEmail(addressBookText(payload, "email", 255, com.nucc.channel.ark.common.util.Constant.REGEX_EMAIL));
        row.setTelephone(addressBookText(payload, "telephone", 32, com.nucc.channel.ark.common.util.Constant.REGEX_TELEPHONE));
        row.setMobileNumber(addressBookText(payload, "mobileNumber", 32, com.nucc.channel.ark.common.util.Constant.REGEX_MOBILE));
        if (row.getId() == null) mapper.insertAddressBook(row);
        else if (mapper.updateAddressBook(row) != 1) throw new IllegalArgumentException("记录不存在");
        SensitiveAddressBookDemo saved = mapper.queryAddressBookById(row.getId());
        if (saved == null) throw new IllegalStateException("保存结果不存在");
        return addressBookValue(saved);
    }

    /** Call before the existing validation/encryption/save flow; no mapper changes required. */
    private String addressBookText(Map<String, Object> payload, String key, int max, String regex) {
        Object raw = payload.get(key);
        if (raw == null) return null;
        if (!(raw instanceof String)) throw new IllegalArgumentException(key + " 非法");
        String value = (String) raw;
        if (value.isEmpty()) return value;
        if (value.contains("[#") || value.contains("#VIEW:")) {
            // AddressBook fields contain one complete value, never mixed text/fragments.
            if (!MARKER.matcher(value).matches()) {
                throw new IllegalArgumentException(key + " 脱敏标记无效，请刷新页面后重新编辑");
            }
            value = editableText(payload, key, max);
        }
        if (value.length() > max || !Pattern.matches(regex, value)) {
            throw new IllegalArgumentException(key + " 格式不正确");
        }
        return value;
    }

    private String editableText(Map<String, Object> payload, String key, int max) {
        Object raw = payload.get(key);
        if (!(raw instanceof String) || ((String) raw).length() > max * 100)
            throw new IllegalArgumentException(key + " 非法");
        String value = (String) raw;
        Matcher matcher = MARKER.matcher(value);
        StringBuffer resolved = new StringBuffer();
        while (matcher.find()) {
            Map<String, Object> tokenInput = new LinkedHashMap<>();
            tokenInput.put("userId", payload.get("userId"));
            tokenInput.put("orgCode", payload.get("orgCode"));
            tokenInput.put("roleId", payload.get("roleId"));
            tokenInput.put("token", matcher.group(2));
            final String encoded;
            try { encoded = json.writeValueAsString(tokenInput); }
            catch (java.io.IOException error) { throw new IllegalStateException("请求参数非法", error); }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(reveal.reveal(encoded)));
        }
        matcher.appendTail(resolved);
        value = resolved.toString();
        if (value.trim().isEmpty() || value.length() > max || value.contains("[#") || value.contains("#VIEW:"))
            throw new IllegalArgumentException(key + " 非法或脱敏标记已失效，请刷新后重试");
        return value;
    }

    private static Long id(Map<String, Object> payload) {
        if (payload.get("id") == null) return null;
        Object value = payload.get("id");
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException("id 非法");
        long id = ((Number) value).longValue();
        if (id <= 0) throw new IllegalArgumentException("id 非法");
        return id;
    }

    private static Map<String, Object> addressBookValue(SensitiveAddressBookDemo row) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", row.getId());
        value.put("name", row.getName());
        value.put("role", row.getRole());
        value.put("email", row.getEmail());
        value.put("telephone", row.getTelephone());
        value.put("mobileNumber", row.getMobileNumber());
        value.put("createdAt", row.getCreatedAt());
        return value;
    }
}
