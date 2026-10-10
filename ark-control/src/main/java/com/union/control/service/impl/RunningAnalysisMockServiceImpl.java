package com.union.control.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.utils.AgentSupport;
import com.union.control.service.RunningAnalysisMockService;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongConsumer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Local mock data used by union-py-app's typed running-analysis tools. */
@Service("runningAnalysisMockService")
public class RunningAnalysisMockServiceImpl implements RunningAnalysisMockService {
    private static final long MIN_BIG_DATA_DELAY_MS = 1000L;
    private static final long MAX_BIG_DATA_DELAY_MS = 1200L;
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private final LongConsumer delay;
    private final ObjectMapper json;
    private final OperationsMockQueries operations;

    @Autowired
    public RunningAnalysisMockServiceImpl(ObjectMapper json) {
        this(RunningAnalysisMockServiceImpl::sleep, json);
    }

    public RunningAnalysisMockServiceImpl(LongConsumer delay) {
        this(delay, new ObjectMapper());
    }

    RunningAnalysisMockServiceImpl(LongConsumer delay, ObjectMapper json) {
        this.delay = delay;
        this.json = json;
        this.operations = new OperationsMockQueries(json);
    }

    public Map<String, Object> getOrgInfo(String input) {
        Map<String, Object> payload = request(input);
        return ok(operations.institutions(requiredString(payload, "orgName")));
    }

    public Map<String, Object> queryBigData(String input) {
        Map<String, Object> payload = request(input);
        String interfaceName = requiredString(payload, "interfaceName");
        if (interfaceName.startsWith("t_indicator_") && !(payload.get("params") instanceof Map))
            throw new QueryException("invalid_query", 422);
        Map<String, Object> params = requiredMap(payload, "params");
        if (interfaceName.startsWith("t_indicator_")) {
            return ok(operations.query(interfaceName, params, payload));
        }
        List<String> days = days(params);
        List<Map<String, Object>> rows;
        if ("runing_cnt.bank".equals(interfaceName)) {
            rows = bankRows(params, days);
        } else if ("runing_cnt.full_link".equals(interfaceName)) {
            rows = fullLinkRows(days);
        } else {
            throw new IllegalArgumentException("interfaceName 非法");
        }
        delay.accept(ThreadLocalRandom.current().nextLong(
                MIN_BIG_DATA_DELAY_MS, MAX_BIG_DATA_DELAY_MS + 1));
        return ok(rows);
    }

    public Map<String, Object> queryChanges(String input) {
        Map<String, Object> payload = request(input);
        return ok(operations.query("changes", operations.conditions(payload), payload));
    }

    public Map<String, Object> queryFaults(String input) {
        Map<String, Object> payload = request(input);
        return ok(operations.query("faults", operations.conditions(payload), payload));
    }

    public Map<String, Object> announceList(String input) {
        Map<String, Object> payload = request(input);
        return ok(operations.legacyDetails("changes", requiredString(payload, "org_code"),
                days(row("startDate", requiredString(payload, "planned_start_time"),
                        "endDate", requiredString(payload, "planned_start_time_end")))));
    }

    public Map<String, Object> getJiraInfo(String input) {
        Map<String, Object> payload = request(input);
        return ok(operations.legacyDetails("faults", requiredString(payload, "orgCode"),
                days(row("startDate", requiredString(payload, "startDate"),
                        "endDate", requiredString(payload, "endDate")))));
    }

    private List<Map<String, Object>> bankRows(Map<String, Object> params, List<String> days) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String code : orgCodes(params.get("orgCodeList"))) rows.addAll(operations.runningRows(code, days));
        return rows;
    }

    private List<Map<String, Object>> fullLinkRows(List<String> days) {
        return operations.runningRows(null, days);
    }

    private static List<String> days(Map<String, Object> params) {
        LocalDate start = parseDate(requiredString(params, "startDate"));
        LocalDate end = parseDate(requiredString(params, "endDate"));
        long count = ChronoUnit.DAYS.between(start, end) + 1;
        if (count < 1 || count > 31) throw new IllegalArgumentException("日期范围必须为 1 到 31 天");
        List<String> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(start.plusDays(i).format(DATE));
        return result;
    }

    private List<String> orgCodes(Object raw) {
        if (raw == null) {
            List<String> result = new ArrayList<>();
            for (Map<String, Object> org : operations.institutions(null)) result.add((String) org.get("orgCode"));
            return result;
        }
        if (!(raw instanceof List) || ((List<?>) raw).isEmpty() || ((List<?>) raw).size() > 100)
            throw new IllegalArgumentException("orgCodeList 非法");
        List<String> result = new ArrayList<>();
        for (Object value : (List<?>) raw) {
            if (!(value instanceof String) || ((String) value).trim().isEmpty() ||
                    ((String) value).trim().length() > 64)
                throw new IllegalArgumentException("orgCodeList 非法");
            result.add(((String) value).trim());
        }
        return result;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("queryBigData 延迟被中断", e);
        }
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value, DATE);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期格式必须为 yyyyMMdd");
        }
    }

    private static String requiredString(Map<String, Object> values, String key) {
        Object raw = values == null ? null : values.get(key);
        if (!(raw instanceof String) || ((String) raw).trim().isEmpty() ||
                ((String) raw).trim().length() > 128)
            throw new IllegalArgumentException(key + " 非法");
        return ((String) raw).trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requiredMap(Map<String, Object> values, String key) {
        Object raw = values == null ? null : values.get(key);
        if (!(raw instanceof Map)) throw new IllegalArgumentException(key + " 非法");
        return (Map<String, Object>) raw;
    }

    private Map<String, Object> request(String input) {
        Map<String, Object> payload = AgentSupport.request(json, input);
        AgentSupport.userId(payload);
        return payload;
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) row.put((String) values[i], values[i + 1]);
        return row;
    }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        return response;
    }

}
