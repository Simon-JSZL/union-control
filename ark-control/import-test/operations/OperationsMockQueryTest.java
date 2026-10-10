package com.union.control.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.service.RunningAnalysisMockService.QueryException;
import org.junit.Test;

import java.util.*;
import static org.junit.Assert.*;

/** 不连接数据库，验证生产字段形态与 py 查询契约。 */
public class OperationsMockQueryTest {
    private final ObjectMapper json = new ObjectMapper();
    private final RunningAnalysisMockServiceImpl service = new RunningAnalysisMockServiceImpl(ms -> {});

    private Map<String, Object> query(String type, String request) throws Exception {
        Map<String, Object> input = json.readValue(request, Map.class);
        input.put("userId", "mock-reader");
        input.put("orgCode", "LOCAL");
        input.put("roleId", "mock-role");
        Map<String, Object> result = "changes".equals(type) ? service.queryChanges(json.writeValueAsString(input))
                : "faults".equals(type) ? service.queryFaults(json.writeValueAsString(input))
                : service.queryBigData(json.writeValueAsString(input));
        return (Map<String, Object>) result.get("data");
    }

    private List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    @Test public void manualQueueAndHistoricalSuccessUseSameQuery() throws Exception {
        Map<String, Object> queue = query("changes", "{\"workflow_states\":[\"manual_pending\"]}");
        assertEquals(1, items(queue).size());
        assertEquals("MOCK-CH-PENDING", items(queue).get(0).get("change_id"));
        Map details = (Map) items(queue).get(0).get("details");
        Map source = (Map) details.get("source_fields");
        assertTrue(source.containsKey("closeChannelDTO"));
        assertTrue(source.containsKey("autoAuditStatus"));
        assertNull(details.get("actual_start")); // executeTime 不是实际执行时间。
        Map<String, Object> history = query("changes", "{\"execution_ratings\":[\"优\"],\"has_fault\":false}");
        assertFalse(items(history).isEmpty());
        assertTrue(((Map) history.get("applied_query")).containsKey("sort_by"));
    }

    @Test public void crossMidnightAndLiteralTextFiltersAreApplied() throws Exception {
        Map<String, Object> page = query("changes", "{\"time_filters\":[{\"field\":\"planned\",\"start\":\"2026-07-19T00:00:00+08:00\",\"end\":\"2026-07-20T00:00:00+08:00\"}],\"text\":{\"fields\":[\"memo\"],\"keywords\":[\"50%\"],\"match\":\"all\"}}");
        assertEquals(1, items(page).size());
        assertEquals("MOCK-CH-CROSS", items(page).get(0).get("change_id"));
        assertTrue(items(query("changes", "{\"text\":{\"fields\":[\"memo\"],\"keywords\":[\"%_\"]}}")).isEmpty());
    }

    @Test public void faultSummaryDoesNotInventTimelineOrConfirmedCause() throws Exception {
        Map row = items(query("faults", "{\"related_change_ids\":[\"MOCK-CH-CROSS\"]}")).get(0);
        assertEquals("unknown", row.get("cause_status"));
        assertNull(row.get("occurred_at"));
        Map details = (Map) row.get("details");
        assertNull(details.get("timeline"));
        assertEquals("1.生产变更", ((Map) details.get("source_fields")).get("faultCause"));
        Map<String, Object> filtered = query("faults", "{\"time_filters\":[{\"field\":\"occurred\",\"start\":\"2026-07-19T00:00:00+08:00\",\"end\":\"2026-07-20T00:00:00+08:00\"}]}");
        assertTrue(items(filtered).isEmpty());
        assertEquals("partial", filtered.get("coverage"));
        assertFalse(((List) filtered.get("gaps")).isEmpty());
    }

    @Test public void cursorIsBoundToQueryIdentityAndSnapshot() throws Exception {
        Map<String, Object> first = query("changes", "{\"page_size\":1}");
        String cursor = (String) first.get("next_cursor");
        assertNotNull(cursor);
        Map<String, Object> second = query("changes", "{\"page_size\":1,\"cursor\":\"" + cursor + "\"}");
        assertNotEquals(items(first).get(0).get("change_id"), items(second).get(0).get("change_id"));
        expectError("invalid_cursor", "changes", "{\"page_size\":2,\"cursor\":\"" + cursor + "\"}");
        expectError("invalid_cursor", "changes", "{\"page_size\":1,\"cursor\":\"0.bad\"}");
        try {
            service.queryChanges("{\"userId\":\"different-user\",\"orgCode\":\"LOCAL\",\"roleId\":\"mock-role\",\"page_size\":1,\"cursor\":\"" + cursor + "\"}");
            fail("Cursor must bind user");
        } catch (QueryException error) { assertEquals("invalid_cursor", error.getCode()); }
    }

    @Test public void sevenTablesKeepNativeFieldsAndSeparateInterfaces() throws Exception {
        String[] tables = {"base_info", "change_info", "change_info_all_biz", "fault_info", "sys_code", "accessed_api_info", "operation_security_cap"};
        for (String table : tables) {
            String params = "base_info".equals(table) ? "{}" : "accessed_api_info".equals(table) ? "{\"snapshot_date\":\"2026-09-29\"}"
                    : "operation_security_cap".equals(table) ? "{\"years\":[2026]}" : "{\"periods\":[{\"start_date\":\"2026-07-18\",\"end_date\":\"2026-07-20\"}]}";
            Map<String, Object> page = query("metrics", "{\"interfaceName\":\"t_indicator_" + table + "\",\"params\":" + params + "}");
            assertFalse(table, items(page).isEmpty());
            Map values = (Map) items(page).get(0).get("values");
            assertTrue(values.containsKey("org_code"));
            if ("change_info".equals(table)) assertEquals(20, values.size());
            if ("fault_info".equals(table)) assertEquals(16, values.size());
            if ("accessed_api_info".equals(table)) {
                assertEquals(21, values.size());
                assertTrue(values.get("change_notice") instanceof String);
            }
            if ("operation_security_cap".equals(table)) assertEquals(26, values.size());
        }
    }

    @Test public void overlapPeriodsAndAggregationKeepDimensionsAndUnknowns() throws Exception {
        String params = "{\"org_codes\":[\"C_MOCK_A\"],\"aggregation\":\"period\",\"periods\":[{\"start_date\":\"2026-07-18\",\"end_date\":\"2026-07-20\"},{\"start_date\":\"2026-07-19\",\"end_date\":\"2026-07-19\"}]}";
        Map<String, Object> page = query("metrics", "{\"interfaceName\":\"t_indicator_fault_info\",\"params\":" + params + "}");
        assertEquals(2, items(page).size());
        for (Map row : items(page)) {
            Map values = (Map) row.get("values");
            assertNull(values.get("fault_review"));
            assertTrue(((Map) row.get("dimensions")).containsKey("period_index"));
        }
    }

    @Test public void rejectsIgnoredConditionsAndFalseHistory() throws Exception {
        expectError("unsupported_filter", "changes", "{\"sql\":\"1=1\"}");
        expectError("invalid_query", "metrics", "{\"interfaceName\":\"t_indicator_fault_info\"}");
        expectError("invalid_query", "changes", "{\"has_fault\":\"false\"}");
        expectError("invalid_query", "changes", "{\"org_codes\":[]}");
        expectError("unsupported_enum", "changes", "{\"workflow_states\":[\"invented\"]}");
        expectError("history_unavailable", "changes", "{\"as_of\":\"2026-07-01T00:00:00+08:00\"}");
        assertTrue(items(query("changes", "{\"version\":\"missing\"}")).isEmpty());
        assertTrue(items(query("changes", "{\"execution_ratings\":[\"中\"]}")).isEmpty()); // 样例未命中不代表枚举非法。
        expectError("unsupported_aggregation", "metrics", "{\"interfaceName\":\"t_indicator_change_info_all_biz\",\"params\":{\"aggregation\":\"period\",\"periods\":[{\"start_date\":\"2026-07-18\",\"end_date\":\"2026-07-20\"}]}}");
    }

    @Test public void nativeRunningRowsKeepCountsAndDeclareMockDurationUnit() throws Exception {
        Map response = service.queryBigData("{\"userId\":\"mock-reader\",\"interfaceName\":\"runing_cnt.bank\",\"params\":{\"orgCodeList\":[\"C_MOCK_A\"],\"startDate\":\"20260718\",\"endDate\":\"20260720\"}}");
        List<Map> rows = (List<Map>) response.get("data");
        assertEquals(3, rows.size());
        assertEquals(150L, ((Number) rows.get(1).get("running_cnt.total_cnt_fail")).longValue());
        assertEquals("ms (mock convention only)", rows.get(1).get("duration_unit"));
        assertEquals(true, rows.get(1).get("mock_data"));
    }

    @Test public void institutionAmbiguityAndCodeAggregatesRetainBusinessDimensions() throws Exception {
        Map<String, Object> orgs = query("metrics", "{\"interfaceName\":\"t_indicator_base_info\",\"params\":{\"name\":\"样例\"}}");
        assertEquals(2, items(orgs).size());
        Map<String, Object> alias = query("metrics", "{\"interfaceName\":\"t_indicator_base_info\",\"params\":{\"name\":\"甲行\"}}");
        assertEquals(1, items(alias).size());
        String request = "{\"interfaceName\":\"t_indicator_sys_code\",\"params\":{\"org_codes\":[\"C_MOCK_A\"],\"aggregation\":\"period\",\"periods\":[{\"start_date\":\"2026-07-18\",\"end_date\":\"2026-07-20\"}]}}";
        Map<String, Object> codes = query("metrics", request);
        assertEquals(2, items(codes).size());
        Map<String, Object> counts = new HashMap<>();
        for (Map<String, Object> item : items(codes)) {
            Map values = (Map) item.get("values");
            counts.put((String) values.get("sys_code"), ((Number) values.get("sys_code_cnt")).intValue());
        }
        assertEquals(124, counts.get("MOCK0001"));
        assertEquals(9, counts.get("MOCK0002"));
        String periods = "{\"interfaceName\":\"t_indicator_fault_info\",\"params\":{\"org_codes\":[\"C_MOCK_A\"],\"aggregation\":\"period\",\"sort_by\":\"query_date\",\"periods\":[{\"start_date\":\"2026-07-20\",\"end_date\":\"2026-07-20\"},{\"start_date\":\"2026-07-18\",\"end_date\":\"2026-07-18\"}]}}";
        Map first = (Map) items(query("metrics", periods)).get(0).get("dimensions");
        assertEquals("2026-07-18", first.get("start_date"));
    }

    @Test public void exportsRealSerializedPagesForPythonContractValidation() throws Exception {
        List<Map<String, Object>> samples = new ArrayList<>();
        for (String type : Arrays.asList("changes", "faults")) {
            samples.add(sample(type, "{}"));
            samples.add(sample(type, "{\"page_size\":1}"));
        }
        samples.add(sample("metrics", "{\"interfaceName\":\"t_indicator_base_info\",\"params\":{\"name\":\"样例\"}}"));
        samples.add(sample("metrics", "{\"interfaceName\":\"t_indicator_accessed_api_info\",\"params\":{\"snapshot_date\":\"2026-09-29\"}}"));
        samples.add(sample("metrics", "{\"interfaceName\":\"t_indicator_operation_security_cap\",\"params\":{\"years\":[2026]}}"));
        for (String table : Arrays.asList("change_info", "change_info_all_biz", "fault_info", "sys_code")) {
            for (String aggregation : Arrays.asList("rows", "period")) {
                if ("change_info_all_biz".equals(table) && "period".equals(aggregation)) continue;
                samples.add(sample("metrics", "{\"interfaceName\":\"t_indicator_" + table + "\",\"params\":{\"aggregation\":\"" + aggregation + "\",\"periods\":[{\"start_date\":\"2026-07-18\",\"end_date\":\"2026-07-20\"}]}}"));
            }
        }
        samples.add(sample("faults", "{\"time_filters\":[{\"field\":\"occurred\",\"start\":\"2026-09-21T00:00:00+08:00\",\"end\":\"2026-09-28T00:00:00+08:00\"}]}"));
        samples.add(sample("changes", "{\"org_codes\":[\"C_MOCK_A\"],\"workflow_states\":[\"auto_reviewed\"],\"time_filters\":[{\"field\":\"planned\",\"start\":\"2026-09-21T00:00:00+08:00\",\"end\":\"2026-09-28T00:00:00+08:00\"}]}"));
        for (String table : Arrays.asList("change_info", "change_info_all_biz", "fault_info", "sys_code"))
            samples.add(sample("metrics", "{\"interfaceName\":\"t_indicator_" + table + "\",\"params\":{\"periods\":[{\"start_date\":\"2026-09-21\",\"end_date\":\"2026-09-27\"}]}}"));
        samples.add(sample("metrics", "{\"interfaceName\":\"t_indicator_accessed_api_info\",\"params\":{\"snapshot_date\":\"2026-09-27\"}}"));
        java.nio.file.Path output = java.nio.file.Paths.get("target/operations-contract-samples.json");
        java.nio.file.Files.createDirectories(output.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), samples);
    }

    @Test public void runningMetricsAreDateKeyedAndReconcileWithMembers() throws Exception {
        List<Map> month = running("full_link", "20260901", "20260930");
        List<Map> week = running("full_link", "20260921", "20260927");
        assertEquals(month.subList(20, 27), week);
        List<Map> members = running("bank", "20260921", "20260927");
        assertEquals(35, members.size());
        for (Map chain : week) {
            long total = 0, failed = 0; double cost = 0;
            for (Map member : members) if (chain.get("statDate").equals(member.get("statDate"))) {
                total += ((Number) member.get("totalTxnCnt")).longValue();
                failed += ((Number) member.get("failTxnCnt")).longValue();
                cost += ((Number) member.get("running_cnt.total_time_cost")).doubleValue();
            }
            assertEquals(total, ((Number) chain.get("totalTxnCnt")).longValue());
            assertEquals(failed, ((Number) chain.get("failTxnCnt")).longValue());
            assertEquals(cost, ((Number) chain.get("running_cnt.total_time_cost")).doubleValue(), 0.001);
            assertEquals(String.format(Locale.ROOT, "%.6f%%", 100.0 * (total - failed) / total), chain.get("sysSuccessPercent"));
        }
        assertTrue(running("full_link", "20261005", "20261005").isEmpty());
        assertEquals(5, items(query("metrics", "{\"interfaceName\":\"t_indicator_base_info\",\"params\":{}}")).size());
    }

    @Test public void septemberEvidenceIsLinkedAndTimeFilterable() throws Exception {
        String window = "[{\"field\":\"occurred\",\"start\":\"2026-09-21T00:00:00+08:00\",\"end\":\"2026-09-28T00:00:00+08:00\"}]";
        Map<String, Object> page = query("faults", "{\"time_filters\":" + window + "}");
        assertEquals(2, items(page).size());
        for (Map<String, Object> fault : items(page)) {
            Map details = (Map) fault.get("details");
            assertEquals("confirmed", fault.get("cause_status"));
            assertFalse(((List) details.get("timeline")).isEmpty());
            String day = fault.get("occurred_at").toString().substring(0, 10);
            String params = "{\"org_codes\":[\"" + fault.get("org_code") + "\"],\"periods\":[{\"start_date\":\"" + day + "\",\"end_date\":\"" + day + "\"}]}";
            Map values = (Map) items(query("metrics", "{\"interfaceName\":\"t_indicator_fault_info\",\"params\":" + params + "}")).get(0).get("values");
            assertEquals(((Map) details.get("impact")).get("duration_minutes"), values.get("impact_time"));
            Map changeValues = (Map) items(query("metrics", "{\"interfaceName\":\"t_indicator_change_info\",\"params\":" + params + "}")).get(0).get("values");
            assertEquals(((Number) values.get("impact_time")).intValue() * 60, changeValues.get("change_off_time"));
            String id = ((List) fault.get("related_change_ids")).get(0).toString();
            Map change = items(query("changes", "{\"change_ids\":[\"" + id + "\"]}")).get(0);
            assertTrue(((List) change.get("related_fault_ids")).contains(fault.get("fault_id")));
        }
        assertEquals(5, items(query("metrics", "{\"interfaceName\":\"t_indicator_accessed_api_info\",\"params\":{\"snapshot_date\":\"2026-09-27\"}}")).size());
    }

    @Test public void legacyToolsReuseTheSameEventsAndInstitutionDirectory() throws Exception {
        List<Map> changes = (List<Map>) service.announceList("{\"userId\":\"mock-reader\",\"org_code\":\"C_MOCK_A\",\"planned_start_time\":\"20260921\",\"planned_start_time_end\":\"20260927\"}").get("data");
        assertEquals(1, changes.size());
        assertEquals("MOCK-CH-20260922-C_MOCK_A", changes.get(0).get("change_id"));
        assertEquals(changes.get(0).get("change_id"), changes.get(0).get("announcementId"));
        List<Map> faults = (List<Map>) service.getJiraInfo("{\"userId\":\"mock-reader\",\"orgCode\":\"C_MOCK_A\",\"startDate\":\"20260921\",\"endDate\":\"20260927\"}").get("data");
        assertEquals(1, faults.size());
        assertEquals(((List) changes.get(0).get("related_fault_ids")).get(0), faults.get(0).get("fault_id"));
        List<Map> orgs = (List<Map>) service.getOrgInfo("{\"userId\":\"mock-reader\",\"orgName\":\"BOC\"}").get("data");
        assertEquals("104100000004", orgs.get(0).get("orgCode"));
        List<Map> empty = (List<Map>) service.getJiraInfo("{\"userId\":\"mock-reader\",\"orgCode\":\"UNKNOWN\",\"startDate\":\"20260921\",\"endDate\":\"20260927\"}").get("data");
        assertTrue(empty.isEmpty());
    }

    private List<Map> running(String scope, String start, String end) throws Exception {
        return (List<Map>) service.queryBigData("{\"userId\":\"mock-reader\",\"interfaceName\":\"runing_cnt." + scope
                + "\",\"params\":{\"startDate\":\"" + start + "\",\"endDate\":\"" + end + "\"}}").get("data");
    }

    private Map<String, Object> sample(String type, String request) throws Exception {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("type", type); sample.put("request", json.readValue(request, Map.class));
        sample.put("page", query(type, request)); return sample;
    }

    private void expectError(String code, String type, String request) throws Exception {
        try { query(type, request); fail("Expected " + code); }
        catch (QueryException error) { assertEquals(code, error.getCode()); }
    }
}
