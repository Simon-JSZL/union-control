package com.union.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.mapper.SensitiveAddressBookDemo;
import com.union.control.mapper.SensitiveDataDemoMapper;
import com.union.control.utils.ServiceSupport;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class SensitiveDataDemoService {
    private final SensitiveDataDemoMapper mapper;
    private final ObjectMapper json;

    public SensitiveDataDemoService(SensitiveDataDemoMapper mapper, ObjectMapper json) {
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

    public List<SensitiveAddressBookDemo> queryAddressBook(String input) {
        ServiceSupport.request(json, input);
        return mapper.queryAddressBook();
    }
}
