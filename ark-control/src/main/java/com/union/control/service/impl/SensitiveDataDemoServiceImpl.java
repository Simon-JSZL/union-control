package com.union.control.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.mapper.SensitiveAddressBookDemo;
import com.union.control.mapper.SensitiveDataDemoMapper;
import com.union.control.service.SensitiveDataDemoService;
import com.union.control.utils.ServiceSupport;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service("sensitiveDataDemoService")
public class SensitiveDataDemoServiceImpl implements SensitiveDataDemoService {
    private final SensitiveDataDemoMapper mapper;
    private final ObjectMapper json;

    public SensitiveDataDemoServiceImpl(SensitiveDataDemoMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    public int insert(String input) {
        Map<String, Object> payload = ServiceSupport.request(json, input);
        return mapper.insert(ServiceSupport.text(payload, "phoneNumber", 64, true));
    }

    public List<Map<String, Object>> query(String input) {
        ServiceSupport.request(json, input);
        return mapper.query();
    }

    public int insertAddressBook(String input) {
        Map<String, Object> payload = ServiceSupport.request(json, input);
        SensitiveAddressBookDemo row = new SensitiveAddressBookDemo();
        row.setName(ServiceSupport.text(payload, "name", 64, true));
        row.setRole(ServiceSupport.text(payload, "role", 32, true));
        row.setEmail(ServiceSupport.text(payload, "email", 255, true));
        row.setTelephone(ServiceSupport.text(payload, "telephone", 32, true));
        row.setMobileNumber(ServiceSupport.text(payload, "mobileNumber", 32, true));
        return mapper.insertAddressBook(row);
    }

    public List<Map<String, Object>> queryAddressBook(String input) {
        ServiceSupport.request(json, input);
        List<Map<String, Object>> result = new ArrayList<>();
        for (SensitiveAddressBookDemo row : mapper.queryAddressBook()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", row.getId());
            value.put("name", row.getName());
            value.put("role", row.getRole());
            value.put("email", row.getEmail());
            value.put("telephone", row.getTelephone());
            value.put("mobileNumber", row.getMobileNumber());
            value.put("createdAt", row.getCreatedAt());
            result.add(value);
        }
        return result;
    }
}
