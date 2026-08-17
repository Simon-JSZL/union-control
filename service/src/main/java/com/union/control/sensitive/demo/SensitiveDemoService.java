package com.union.control.sensitive.demo;

import com.epcc.arkweb.helper.AuthContextHolder;
import com.epcc.arkweb.model.ShiroUser;
import com.union.control.service.ServiceExceptions;
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
        String userId = currentUserId();
        Object raw = payload == null ? null : payload.get("value");
        if (!(raw instanceof String)) throw new IllegalArgumentException("value必须是字符串");
        SensitiveDemoRecord record = new SensitiveDemoRecord(userId, (String) raw);
        mapper.insert(record);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", record.getId());
        return response;
    }

    public Map<String, Object> list(String cookie) {
        List<SensitiveDemoRecord> rows = mapper.list(currentUserId());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", rows);
        return response;
    }

    private static String currentUserId() {
        ShiroUser user = AuthContextHolder.getAuthUserDetails();
        if (user == null || user.getLoginName() == null || user.getLoginName().trim().isEmpty())
            throw new ServiceExceptions.UnauthorizedException();
        return user.getLoginName();
    }
}
