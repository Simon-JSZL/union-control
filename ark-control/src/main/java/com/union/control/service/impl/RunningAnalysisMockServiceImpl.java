package com.union.control.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.utils.AgentSupport;
import com.union.control.service.RunningAnalysisMockService;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    private static final List<Org> ORGS = Arrays.asList(
            new Org("104100000004", "中国银行", "中行", "BOC", "中国银行股份有限公司"),
            new Org("102100099996", "中国工商银行", "工行", "ICBC"),
            new Org("103100000026", "中国农业银行", "农行", "ABC")
    );
    private final LongConsumer delay;
    private final ObjectMapper json;

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
    }

    public Map<String, Object> getOrgInfo(String input) {
        Map<String, Object> payload = request(input);
        String query = requiredString(payload, "orgName").toLowerCase(Locale.ROOT);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Org org : ORGS) {
            if (org.matches(query)) rows.add(org.row());
        }
        return ok(rows);
    }

    public Map<String, Object> queryBigData(String input) {
        Map<String, Object> payload = request(input);
        String interfaceName = requiredString(payload, "interfaceName");
        Map<String, Object> params = requiredMap(payload, "params");
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

    public Map<String, Object> announceList(String input) {
        Map<String, Object> payload = request(input);
        String orgCode = requiredString(payload, "org_code");
        String startDate = validDate(requiredString(payload, "planned_start_time"));
        validDate(requiredString(payload, "planned_start_time_end"));
        String orgName = orgName(orgCode);
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row(
                "announcementId", "CHG-MOCK-20260712001",
                "orgCode", orgCode,
                "orgName", orgName,
                "changeTitle", "核心支付链路灰度变更",
                "plannedStartTime", startDate + " 01:00:00",
                "plannedEndTime", startDate + " 03:00:00",
                "status", "已完成",
                "executionEvaluation", "执行正常，变更后交易成功率无明显波动",
                "impactScope", "网银、手机银行支付交易"
        ));
        rows.add(row(
                "announcementId", "CHG-MOCK-20260712002",
                "orgCode", orgCode,
                "orgName", orgName,
                "changeTitle", "前置系统网络策略调整",
                "plannedStartTime", startDate + " 02:00:00",
                "plannedEndTime", startDate + " 04:00:00",
                "status", "已完成",
                "executionEvaluation", "执行后平均耗时短时升高，已恢复",
                "impactScope", "成员机构接入前置链路"
        ));
        return ok(rows);
    }

    public Map<String, Object> getJiraInfo(String input) {
        Map<String, Object> payload = request(input);
        String orgCode = requiredString(payload, "orgCode");
        String startDate = validDate(requiredString(payload, "startDate"));
        validDate(requiredString(payload, "endDate"));
        String orgName = orgName(orgCode);
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row(
                "issueKey", "OPS-20260712-001",
                "orgCode", orgCode,
                "orgName", orgName,
                "faultTime", startDate + " 10:24:00",
                "recoverTime", startDate + " 10:41:00",
                "faultLevel", "P2",
                "impactScope", "部分支付交易",
                "impactDescription", "支付交易失败率短时升高，影响约 320 笔",
                "faultCause", "成员机构前置连接池耗尽",
                "status", "已恢复"
        ));
        rows.add(row(
                "issueKey", "OPS-20260712-009",
                "orgCode", orgCode,
                "orgName", orgName,
                "faultTime", startDate + " 22:18:00",
                "recoverTime", startDate + " 22:30:00",
                "faultLevel", "P3",
                "impactScope", "查询类交易",
                "impactDescription", "查询平均耗时升高，未形成大面积失败",
                "faultCause", "下游数据库慢查询",
                "status", "已关闭"
        ));
        return ok(rows);
    }

    private static List<Map<String, Object>> bankRows(
            Map<String, Object> params, List<String> days) {
        List<String> orgCodes = orgCodes(params.get("orgCodeList"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String orgCode : orgCodes) {
            for (int index = 0; index < days.size(); index++) {
                int failCount = 5 + index % 4;
                int totalCount = 132000 + index * 1300;
                rows.add(row(
                        "statDate", days.get(index),
                        "orgCode", orgCode,
                        "orgName", orgName(orgCode),
                        "sysSuccessPercent", String.format(Locale.ROOT, "%.4f%%", 99.997 - index * 0.0003),
                        "avgCostMs", 38.5 + index * 0.7,
                        "totalTxnCnt", totalCount,
                        "failTxnCnt", failCount,
                        "failRate", String.format(Locale.ROOT, "%.4f%%", failCount * 100.0 / totalCount)
                ));
            }
        }
        return rows;
    }

    private static List<Map<String, Object>> fullLinkRows(List<String> days) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < days.size(); index++) {
            int failCount = 31 + index % 8;
            int totalCount = 2680000 + index * 25000;
            rows.add(row(
                    "statDate", days.get(index),
                    "sysSuccessPercent", String.format(Locale.ROOT, "%.4f%%", 99.9982 - index * 0.0001),
                    "avgCostMs", 41.2 + index * 0.3,
                    "totalTxnCnt", totalCount,
                    "failTxnCnt", failCount,
                    "failRate", String.format(Locale.ROOT, "%.4f%%", failCount * 100.0 / totalCount)
            ));
        }
        return rows;
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

    private static List<String> orgCodes(Object raw) {
        if (raw == null) {
            List<String> result = new ArrayList<>();
            for (Org org : ORGS) result.add(org.code);
            return result;
        }
        if (!(raw instanceof List) || ((List<?>) raw).isEmpty() || ((List<?>) raw).size() > 20)
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

    private static String orgName(String orgCode) {
        for (Org org : ORGS) {
            if (org.code.equals(orgCode)) return org.name;
        }
        return "函数调用测试银行";
    }

    private static String validDate(String value) {
        parseDate(value);
        return value;
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

    private static class Org {
        private final String code;
        private final String name;
        private final List<String> aliases;

        private Org(String code, String name, String... aliases) {
            this.code = code;
            this.name = name;
            this.aliases = Collections.unmodifiableList(Arrays.asList(aliases));
        }

        private boolean matches(String query) {
            if (name.toLowerCase(Locale.ROOT).contains(query)) return true;
            for (String alias : aliases) {
                if (alias.toLowerCase(Locale.ROOT).contains(query)) return true;
            }
            return false;
        }

        private Map<String, Object> row() {
            return RunningAnalysisMockServiceImpl.row(
                    "orgCode", code, "orgName", name, "aliases", aliases);
        }
    }
}
