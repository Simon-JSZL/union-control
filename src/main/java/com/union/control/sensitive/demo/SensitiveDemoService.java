package com.union.control.sensitive.demo;

import com.union.control.service.LocalAuth;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SensitiveDemoService {
    private final SensitiveDemoMapper mapper;

    public SensitiveDemoService(SensitiveDemoMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, Object> create(String cookie, Map<String, Object> payload) {
        String userId = LocalAuth.authenticate(cookie);
        Object raw = payload == null ? null : payload.get("value");
        if (!(raw instanceof String)) throw new IllegalArgumentException("value必须是字符串");
        SensitiveDemoRecord record = new SensitiveDemoRecord(userId, (String) raw);
        mapper.insert(record);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", record.getId());
        return response;
    }

    public Map<String, Object> list(String cookie) {
        List<SensitiveDemoRecord> rows = mapper.list(LocalAuth.authenticate(cookie));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", rows);
        return response;
    }
}
