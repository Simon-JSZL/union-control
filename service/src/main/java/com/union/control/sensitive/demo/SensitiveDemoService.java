package com.union.control.sensitive.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.utils.ServiceSupport;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SensitiveDemoService {
    private final SensitiveDemoMapper mapper;
    private final ObjectMapper json;

    public SensitiveDemoService(SensitiveDemoMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    public Map<String, Object> create(String input) {
        Map<String, Object> payload = ServiceSupport.request(json, input);
        String userId = ServiceSupport.userId(payload);
        Object raw = payload == null ? null : payload.get("value");
        if (!(raw instanceof String)) throw new IllegalArgumentException("value必须是字符串");
        SensitiveDemoRecord record = new SensitiveDemoRecord(userId, (String) raw);
        mapper.insert(record);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", record.getId());
        return response;
    }

    public Map<String, Object> list(String input) {
        List<SensitiveDemoRecord> rows = mapper.list(
                ServiceSupport.userId(ServiceSupport.request(json, input)));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", rows);
        return response;
    }

}
