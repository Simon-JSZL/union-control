package com.union.control.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.service.RunningAnalysisMockService.QueryException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 本地固定合成数据查询；保留生产字段形态，不读取生产数据或模拟历史版本。 */
final class OperationsMockQueries {
    private static final String BASE = "t_indicator_base_info";
    private static final String API = "t_indicator_accessed_api_info";
    private static final String CAP = "t_indicator_operation_security_cap";
    private static final String ALL_BIZ = "t_indicator_change_info_all_biz";
    private static final String SYS = "t_indicator_sys_code";
    private static final Set<String> COMMON = set("org_codes", "page_size", "cursor", "as_of", "version", "sort_by", "sort_order");
    private static final Map<String, String> CHANGE = paths(
            "change_ids", "change_id", "related_fault_ids", "related_fault_ids", "related_change_ids", "related_change_ids",
            "workflow_states", "workflow_state", "source_statuses", "details.source_status",
            "business_types", "details.business_type", "operations", "details.operation",
            "impact_businesses", "details.impact_businesses", "exemption_codes", "details.exemption_code",
            "rule_ids", "details.rule_assessment.hits.rule_id", "rule_results", "details.rule_assessment.hits.result",
            "execution_ratings", "details.execution_rating", "notify_bank", "details.notify_bank",
            "notify_org", "details.notify_org", "is_urgent", "details.is_urgent");
    private static final Map<String, String> FAULT = paths(
            "fault_ids", "fault_id", "related_change_ids", "related_change_ids", "business_types", "details.business_types",
            "levels", "details.level", "statuses", "details.status", "cause_categories", "details.cause_category");
    private static final Map<String, String> INSTITUTION = paths(
            "org_types", "values.org_type", "categories", "values.org_category", "scale_types", "values.bank_scale_type",
            "provinces", "values.org_addr_province", "cities", "values.org_addr_city", "parent_org_codes", "values.parent_org_code");
    private static final Map<String, String> CHANGE_TIMES = paths(
            "created", "details.created_at", "planned", "planned_start,planned_end",
            "actual", "details.actual_start,details.actual_end", "audited", "details.audited_at", "updated", "details.updated_at");
    private static final Map<String, String> FAULT_TIMES = paths(
            "occurred", "occurred_at", "discovered", "details.discovered_at", "impact", "details.impact.start,details.impact.end",
            "recovered", "recovered_at", "updated", "details.updated_at");
    private static final Map<String, String> CHANGE_TEXT = paths(
            "memo", "details.memo", "impact_description", "details.impact_description",
            "audit_opinion", "details.manual_reviews", "execution_description", "details.execution_description");
    private static final Map<String, String> FAULT_TEXT = paths(
            "phenomenon", "details.phenomenon", "impact_description", "details.impact", "cause", "details.cause",
            "handling", "details.handling", "review", "details.review", "remediation", "details.remediation");
    private final ObjectMapper json;
    private final Map<String, Object> fixture;
    private final byte[] cursorKey = new byte[32];

    OperationsMockQueries(ObjectMapper json) {
        this.json = json;
        new SecureRandom().nextBytes(cursorKey);
        try (InputStream input = getClass().getResourceAsStream("/mock/operations-v1.json")) {
            if (input == null) throw new IllegalStateException("运维 mock 数据未打包");
            fixture = json.readValue(input, new TypeReference<Map<String, Object>>() {});
        } catch (Exception error) {
            throw new IllegalStateException("运维 mock 数据加载失败", error);
        }
    }

    Map<String, Object> conditions(Map<String, Object> payload) {
        Map<String, Object> query = new LinkedHashMap<>(payload);
        query.keySet().removeAll(set("userId", "orgCode", "roleId"));
        return query;
    }

    List<Map<String, Object>> institutions(String name) {
        Map<String, Object> dataset = map(map(fixture.get("indicators")).get(BASE));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : records(dataset.get("items"))) {
            Object code = first(item, "values.org_code"), orgName = first(item, "values.org_name");
            Object aliases = map(dataset.get("aliases")).get(code);
            String query = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (flatten(orgName).contains(query) || flatten(aliases).contains(query) || code.equals(name))
                result.add(row("orgCode", code, "orgName", orgName, "aliases", aliases));
        }
        return result;
    }

    List<Map<String, Object>> runningRows(String orgCode, List<String> days) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String day : days) {
            long total = 0, failed = 0; BigDecimal cost = BigDecimal.ZERO;
            for (Map<String, Object> record : records(fixture.get("running"))) {
                if (!day.equals(record.get("running_cnt.ymd")) || (orgCode != null && !orgCode.equals(record.get("running_cnt.bank_code")))) continue;
                total += ((Number) record.get("running_cnt.total_cnt")).longValue();
                failed += ((Number) record.get("running_cnt.total_cnt_fail")).longValue();
                cost = cost.add(new BigDecimal(record.get("running_cnt.total_time_cost").toString()));
            }
            if (total == 0) continue;
            // A chain row covers exactly the mock members present on this day, never the production universe.
            Map<String, Object> result = row("statDate", day, "totalTxnCnt", total, "failTxnCnt", failed,
                    "sysSuccessPercent", String.format(Locale.ROOT, "%.6f%%", (total - failed) * 100.0 / total),
                    "failRate", String.format(Locale.ROOT, "%.6f%%", failed * 100.0 / total),
                    "avgCostMs", cost.doubleValue() / total,
                    "running_cnt.total_cnt", total, "running_cnt.total_cnt_fail", failed,
                    "running_cnt.total_time_cost", cost, "running_cnt.ymd", day, "running_cnt.biz_type", "day",
                    "mock_data", true, "duration_unit", "ms (mock convention only)",
                    "coverage", "partial", "coverage_note", fixture.get("notice"),
                    "source_ref", "mock:running_cnt:" + (orgCode == null ? "full_link" : orgCode) + ":" + day);
            if (orgCode != null) {
                result.put("orgCode", orgCode); result.put("running_cnt.bank_code", orgCode);
                for (Map<String, Object> institution : records(map(map(fixture.get("indicators")).get(BASE)).get("items")))
                    if (orgCode.equals(first(institution, "values.org_code"))) result.put("orgName", first(institution, "values.org_name"));
            }
            rows.add(result);
        }
        return rows;
    }

    List<Map<String, Object>> legacyDetails(String type, String orgCode, List<String> days) {
        List<Map<String, Object>> rows = new ArrayList<>();
        LocalDate start = LocalDate.parse(days.get(0), DateTimeFormatter.BASIC_ISO_DATE);
        LocalDate end = LocalDate.parse(days.get(days.size() - 1), DateTimeFormatter.BASIC_ISO_DATE).plusDays(1);
        for (Map<String, Object> record : records(fixture.get(type))) {
            if (!orgCode.equals(record.get("org_code"))) continue;
            boolean change = "changes".equals(type);
            Object lo = record.get(change ? "planned_start" : "occurred_at");
            Object hi = record.get(change ? "planned_end" : "recovered_at");
            if (lo == null || hi == null) continue;
            if (instant(lo).toInstant().isBefore(end.atStartOfDay().toInstant(java.time.ZoneOffset.ofHours(8)))
                    && instant(hi).toInstant().isAfter(start.atStartOfDay().toInstant(java.time.ZoneOffset.ofHours(8)))) {
                Map<String, Object> result = copy(record);
                result.put("mock_data", true); result.put("orgCode", orgCode);
                List<Map<String, Object>> orgs = institutions(orgCode);
                result.put("orgName", orgs.isEmpty() ? null : orgs.get(0).get("orgName"));
                if (change) {
                    result.putAll(row("announcementId", record.get("change_id"), "changeTitle", first(record, "details.memo"),
                            "plannedStartTime", lo, "plannedEndTime", hi, "status", record.get("workflow_state"),
                            "executionEvaluation", first(record, "details.execution_description"),
                            "impactScope", first(record, "details.impact_description")));
                } else {
                    result.putAll(row("issueKey", record.get("fault_id"), "faultTime", lo, "recoverTime", hi,
                            "faultLevel", first(record, "details.level"), "status", first(record, "details.status"),
                            "impactScope", first(record, "details.business_types"),
                            "impactDescription", first(record, "details.impact.description"), "faultCause", first(record, "details.cause")));
                }
                rows.add(result);
            }
        }
        return rows;
    }

    Map<String, Object> query(String type, Map<String, Object> input, Map<String, Object> identity) {
        boolean changes = "changes".equals(type), faults = "faults".equals(type), details = changes || faults;
        Map<String, Object> dataset = details ? Collections.emptyMap() : map(map(fixture.get("indicators")).get(type));
        if (!details && dataset.isEmpty()) throw error("unsupported_enum");
        if (!details) keys(identity, set("interfaceName", "params", "userId", "orgCode", "roleId"));
        Map<String, String> filters = changes ? CHANGE : faults ? FAULT : BASE.equals(type) ? INSTITUTION : Collections.emptyMap();
        Map<String, String> times = changes ? CHANGE_TIMES : FAULT_TIMES;
        Map<String, String> texts = changes ? CHANGE_TEXT : FAULT_TEXT;
        List<Map<String, Object>> source = records(details ? fixture.get(type) : dataset.get("items"));
        Map<String, Object> q = normalize(type, input, filters, times, texts, source);
        Set<String> gaps = new LinkedHashSet<>();
        gaps.add(String.valueOf(fixture.get("notice")));
        if (isDaily(type)) gaps.add("日指标覆盖五家样本机构的 2026-09-01 至 2026-10-04；另保留甲乙两家 7 月 18–20 日案例；范围外为空不代表无事件。");
        if (API.equals(type)) gaps.add("接口快照覆盖 2026-09-01 至 2026-10-04，不代表当前接口状态。");
        if (CAP.equals(type)) gaps.add("仅有 2026 年的合成能力数据。");
        if (faults) gaps.add("七月历史摘要仍保留未知字段；九月至十月合成案例提供独立设定的时间线、影响及复盘。");
        if (ALL_BIZ.equals(type)) gaps.add("all_biz 仅含生产消费 SQL 已确认的八个字段；完整 DDL 和生成规则未提供。");
        List<Map<String, Object>> selected = new ArrayList<>();
        for (Map<String, Object> record : source) {
            if (!matches(record, q, filters, times, texts, dataset, gaps, details)) continue;
            if (isDaily(type)) {
                List<?> periods = (List<?>) q.get("periods");
                LocalDate day = LocalDate.parse(String.valueOf(map(record.get("values")).get("query_date")), DateTimeFormatter.BASIC_ISO_DATE);
                for (int i = 0; i < periods.size(); i++) {
                    Map<String, Object> period = map(periods.get(i));
                    if (!day.isBefore(date(period.get("start_date"))) && !day.isAfter(date(period.get("end_date")))) {
                        Map<String, Object> copy = copy(record);
                        map(copy.get("dimensions")).put("period_index", i);
                        selected.add(copy);
                    }
                }
            } else selected.add(copy(record));
        }
        if ("period".equals(q.get("aggregation"))) selected = aggregate(selected, q, dataset, type, gaps);
        final String sort = "period".equals(q.get("aggregation")) && "query_date".equals(q.get("sort_by"))
                ? "dimensions.start_date" : sortPath(type, (String) q.get("sort_by"));
        final int direction = "desc".equals(q.get("sort_order")) ? -1 : 1;
        selected.sort((a, b) -> {
            int c = compare(first(a, sort), first(b, sort));
            if (c != 0) return c * direction;
            c = String.valueOf(a.get("source_ref")).compareTo(String.valueOf(b.get("source_ref")));
            if (c != 0) return c * direction;
            return compare(first(a, "dimensions.period_index"), first(b, "dimensions.period_index")) * direction;
        });
        int start = offset(type, q, identity);
        if (start > selected.size()) throw error("invalid_cursor");
        int end = Math.min(selected.size(), start + (Integer) q.get("page_size"));
        List<Map<String, Object>> items = selected.subList(start, end);
        Set<String> unavailable = new LinkedHashSet<>();
        for (Map<String, Object> item : items) for (Object field : (List<?>) item.get("unavailable_fields")) unavailable.add(String.valueOf(field));
        Map<String, Object> definitions = new LinkedHashMap<>(map(dataset.get("definitions")));
        definitions.put("mock_data", String.valueOf(fixture.get("notice")));
        if (details) definitions.put("workflow_state", "合成场景明确指定；未将原始状态数字推断为生产审核流程状态。");
        return row("items", new ArrayList<>(items), "total", selected.size(),
                "next_cursor", end < selected.size() ? cursor(end, type, q, identity) : null,
                "applied_query", q, "data_updated_at", fixture.get("data_updated_at"),
                "coverage", "partial", "gaps", new ArrayList<>(gaps), "unavailable_fields", new ArrayList<>(unavailable),
                "units", dataset.containsKey("units") ? dataset.get("units") : Collections.emptyMap(), "definitions", definitions);
    }

    private Map<String, Object> normalize(String type, Map<String, Object> input, Map<String, String> filters,
                                          Map<String, String> times, Map<String, String> texts, List<Map<String, Object>> source) {
        Map<String, Object> q = copy(input);
        Set<String> allowed = new HashSet<>(COMMON);
        allowed.addAll(filters.keySet());
        if (isDetail(type)) allowed.addAll(set("time_filters", "text"));
        if ("changes".equals(type)) allowed.add("has_fault");
        if (BASE.equals(type)) allowed.add("name");
        if (isDaily(type)) allowed.addAll(set("periods", "aggregation"));
        if (SYS.equals(type)) allowed.add("sys_codes");
        if (API.equals(type)) allowed.add("snapshot_date");
        if (CAP.equals(type)) allowed.add("years");
        keys(q, allowed);
        for (Object value : q.values()) if (value == null) throw error("invalid_query");
        q.putIfAbsent("page_size", 20); q.putIfAbsent("sort_order", "asc");
        q.putIfAbsent("sort_by", "changes".equals(type) ? "change_id" : "faults".equals(type) ? "fault_id" : "org_code");
        integer(q.get("page_size"), 1, 100);
        choice(q.get("sort_order"), set("asc", "desc"));
        choice(q.get("sort_by"), sorts(type));
        if (q.containsKey("as_of")) { instant(q.get("as_of")); throw error("history_unavailable"); }
        if (q.containsKey("version")) string(q.get("version"), 128);
        if (q.containsKey("cursor")) string(q.get("cursor"), 2048);
        if (q.containsKey("org_codes")) strings(q.get("org_codes"), 100, 128);
        for (String key : filters.keySet()) {
            if (!q.containsKey(key)) continue;
            if (set("notify_bank", "notify_org", "is_urgent").contains(key)) {
                if (!(q.get(key) instanceof Boolean)) throw error("invalid_query");
            } else {
                List<String> values = strings(q.get(key), 100, 128);
                // IDs can legitimately be absent. Native business codes must be known by this mock dictionary.
                if (!key.endsWith("_ids") && !"parent_org_codes".equals(key)) {
                    Set<Object> known = new HashSet<>();
                    for (Map<String, Object> record : source) known.addAll(at(record, filters.get(key)));
                    Object dictionary = map(map(fixture.get("enums")).get(type)).get(key);
                    if (dictionary instanceof List) known.addAll((List<?>) dictionary);
                    if ("workflow_states".equals(key)) known.addAll(set("manual_pending", "auto_reviewed", "manual_reviewed", "cancelled"));
                    if (known.isEmpty()) throw error("unsupported_filter");
                    if (!known.containsAll(values)) throw error("unsupported_enum");
                }
            }
        }
        if (q.containsKey("has_fault") && !(q.get("has_fault") instanceof Boolean)) throw error("invalid_query");
        if (q.containsKey("time_filters")) {
            for (Object raw : list(q.get("time_filters"), 5)) {
                Map<String, Object> time = requiredMap(raw);
                keys(time, set("field", "start", "end")); choice(time.get("field"), times.keySet());
                if (!instant(time.get("start")).isBefore(instant(time.get("end")))) throw error("invalid_query");
            }
        }
        if (q.containsKey("text")) {
            Map<String, Object> text = requiredMap(q.get("text"));
            keys(text, set("fields", "keywords", "match"));
            for (String field : strings(text.get("fields"), texts.size(), 128)) choice(field, texts.keySet());
            strings(text.get("keywords"), 20, 2000); text.putIfAbsent("match", "any");
            choice(text.get("match"), set("any", "all"));
        }
        if (q.containsKey("name")) string(q.get("name"), 128);
        if (isDaily(type)) {
            for (Object raw : list(q.get("periods"), 12)) {
                Map<String, Object> period = requiredMap(raw); keys(period, set("start_date", "end_date"));
                if (date(period.get("start_date")).isAfter(date(period.get("end_date")))) throw error("invalid_query");
            }
            q.putIfAbsent("aggregation", "rows"); choice(q.get("aggregation"), set("rows", "period"));
            if (ALL_BIZ.equals(type) && "period".equals(q.get("aggregation"))) throw error("unsupported_aggregation");
        }
        if (q.containsKey("sys_codes")) strings(q.get("sys_codes"), 100, 128);
        if (API.equals(type)) date(q.get("snapshot_date"));
        if (CAP.equals(type)) for (Object year : list(q.get("years"), 20)) integer(year, 1900, 2200);
        return q;
    }

    private boolean matches(Map<String, Object> record, Map<String, Object> q, Map<String, String> filters,
                            Map<String, String> times, Map<String, String> texts, Map<String, Object> dataset,
                            Set<String> gaps, boolean details) {
        if (q.containsKey("version") && !q.get("version").equals(record.get("version"))) return false;
        if (!intersects(q, "org_codes", at(record, details ? "org_code" : "values.org_code"))) return false;
        for (Map.Entry<String, String> filter : filters.entrySet()) {
            String key = filter.getKey();
            if (!q.containsKey(key)) continue;
            List<Object> values = at(record, filter.getValue());
            if (values.isEmpty()) gaps.add("部分记录缺少过滤字段 " + key + "，未将未知值作为匹配结果。");
            if (q.get(key) instanceof Boolean) { if (!values.contains(q.get(key))) return false; }
            else if (!intersects(q, key, values)) return false;
        }
        if (q.containsKey("has_fault")) {
            Object relations = record.get("related_fault_ids");
            if (relations == null) { gaps.add("部分故障关联未知，未作为 has_fault 匹配结果。"); return false; }
            if (!q.get("has_fault").equals(!((List<?>) relations).isEmpty())) return false;
        }
        if (q.containsKey("time_filters")) for (Object raw : (List<?>) q.get("time_filters")) {
            Map<String, Object> filter = map(raw);
            String[] paths = times.get(filter.get("field")).split(",");
            Object start = first(record, paths[0]), end = first(record, paths[paths.length - 1]);
            if (start == null || end == null) { gaps.add("部分记录缺少 " + filter.get("field") + " 时间，未把计划窗口当作故障或实际执行时间。"); return false; }
            OffsetDateTime lo = instant(filter.get("start")), hi = instant(filter.get("end"));
            if (paths.length == 1) { if (instant(start).isBefore(lo) || !instant(start).isBefore(hi)) return false; }
            else if (!instant(start).isBefore(hi) || !instant(end).isAfter(lo)) return false;
        }
        if (q.containsKey("text")) {
            Map<String, Object> text = map(q.get("text")); List<String> haystacks = new ArrayList<>();
            for (Object field : (List<?>) text.get("fields")) for (Object value : at(record, texts.get(field))) haystacks.add(flatten(value));
            if (haystacks.isEmpty()) gaps.add("部分记录缺少指定文本字段，未作为文本匹配结果。");
            boolean all = "all".equals(text.get("match")); boolean result = all;
            for (Object word : (List<?>) text.get("keywords")) {
                boolean hit = haystacks.stream().anyMatch(value -> value.contains(String.valueOf(word).toLowerCase(Locale.ROOT)));
                result = all ? result && hit : result || hit;
            }
            if (!result) return false;
        }
        if (q.containsKey("name")) {
            String name = String.valueOf(q.get("name")).toLowerCase(Locale.ROOT);
            String code = String.valueOf(first(record, "values.org_code"));
            if (!flatten(first(record, "values.org_name")).contains(name)
                    && !flatten(map(dataset.get("aliases")).get(code)).contains(name)) return false;
        }
        if (!intersects(q, "sys_codes", at(record, "values.sys_code"))) return false;
        if (q.containsKey("snapshot_date") && !q.get("snapshot_date").equals(first(record, "dimensions.snapshot_date"))) return false;
        return intersects(q, "years", at(record, "dimensions.year"));
    }

    private List<Map<String, Object>> aggregate(List<Map<String, Object>> rows, Map<String, Object> q,
                                                Map<String, Object> dataset, String type, Set<String> gaps) {
        Map<List<Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> record : rows) {
            List<Object> key = Arrays.asList(first(record, "dimensions.org_code"), first(record, "dimensions.period_index"), first(record, "dimensions.sys_code"));
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(record);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        Set<?> sums = new HashSet<>((List<?>) dataset.get("sum_fields"));
        for (List<Map<String, Object>> group : groups.values()) {
            Map<String, Object> output = copy(group.get(0)), values = map(output.get("values")), dims = map(output.get("dimensions"));
            int index = ((Number) dims.get("period_index")).intValue();
            Map<String, Object> period = map(((List<?>) q.get("periods")).get(index));
            dims.remove("query_date"); dims.remove("ymd"); dims.putAll(period);
            dims.put("source_row_count", group.size());
            List<String> missing = new ArrayList<>();
            for (String field : new ArrayList<>(values.keySet())) {
                if (sums.contains(field)) {
                    BigDecimal sum = BigDecimal.ZERO; boolean complete = true;
                    for (Map<String, Object> record : group) {
                        Object value = map(record.get("values")).get(field);
                        if (!(value instanceof Number)) { complete = false; break; }
                        sum = sum.add(new BigDecimal(value.toString()));
                    }
                    values.put(field, complete ? sum : null);
                } else if (!set("org_code", "org_name", "sys_code", "sys_code_description").contains(field)) values.put(field, null);
                if (values.get(field) == null) missing.add("values." + field);
            }
            output.put("source_ref", "mock:aggregate:" + type + ":" + dims.get("org_code") + ":" + index + ":" + dims.getOrDefault("sys_code", "all"));
            output.put("unavailable_fields", missing);
            if (!missing.isEmpty()) gaps.add("聚合只累加已确认可加字段；未知字段返回 null，业务日期范围见 dimensions。");
            result.add(output);
        }
        return result;
    }

    private int offset(String type, Map<String, Object> q, Map<String, Object> identity) {
        if (!q.containsKey("cursor")) return 0;
        try {
            String[] parts = String.valueOf(q.get("cursor")).split("\\.", -1);
            if (parts.length != 2) throw error("invalid_cursor");
            int value = Integer.parseInt(parts[0]);
            if (value <= 0 || !MessageDigest.isEqual(cursor(value, type, q, identity).getBytes(StandardCharsets.UTF_8), String.valueOf(q.get("cursor")).getBytes(StandardCharsets.UTF_8))) throw error("invalid_cursor");
            return value;
        } catch (RuntimeException error) { throw error("invalid_cursor"); }
    }

    private String cursor(int offset, String type, Map<String, Object> q, Map<String, Object> identity) {
        Map<String, Object> bound = new TreeMap<>(q); bound.remove("cursor");
        bound.put("_type", type); bound.put("_offset", offset); bound.put("_snapshot", fixture);
        for (String key : set("userId", "orgCode", "roleId")) bound.put("_" + key, identity.get(key));
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(cursorKey, "HmacSHA256"));
            byte[] signature = mac.doFinal(json.writeValueAsBytes(canonical(bound)));
            return offset + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (Exception error) { throw new IllegalStateException("分页凭据生成失败", error); }
    }

    private static Object canonical(Object value) {
        if (value instanceof Map) {
            Map<String, Object> result = new TreeMap<>();
            map(value).forEach((k, v) -> result.put(k, canonical(v))); return result;
        }
        if (value instanceof List) { List<Object> result = new ArrayList<>(); for (Object v : (List<?>) value) result.add(canonical(v)); return result; }
        return value;
    }

    private static String sortPath(String type, String field) {
        if (!isDetail(type)) return "query_date".equals(field) || "year".equals(field) ? "dimensions." + field : "values." + field;
        return set("created_at", "actual_start", "audited_at", "updated_at", "discovered_at").contains(field) ? "details." + field : field;
    }
    private static Set<String> sorts(String type) {
        if ("changes".equals(type)) return set("change_id", "created_at", "planned_start", "actual_start", "audited_at", "updated_at");
        if ("faults".equals(type)) return set("fault_id", "occurred_at", "discovered_at", "recovered_at", "updated_at");
        if (BASE.equals(type)) return set("org_code", "org_name");
        if (API.equals(type)) return set("org_code");
        return set("org_code", CAP.equals(type) ? "year" : "query_date");
    }
    private static boolean isDetail(String type) { return "changes".equals(type) || "faults".equals(type); }
    private static boolean isDaily(String type) { return !isDetail(type) && !set(BASE, API, CAP).contains(type); }
    private static boolean intersects(Map<String, Object> q, String key, List<Object> values) {
        return !q.containsKey(key) || !Collections.disjoint((List<?>) q.get(key), values);
    }
    private static List<Object> at(Object value, String path) {
        List<Object> values = new ArrayList<>(); collect(value, path.split("\\."), 0, values); return values;
    }
    private static void collect(Object value, String[] path, int index, List<Object> result) {
        if (value == null) return;
        if (value instanceof List) for (Object child : (List<?>) value) collect(child, path, index, result);
        else if (index == path.length) result.add(value);
        else if (value instanceof Map) collect(map(value).get(path[index]), path, index + 1, result);
    }
    private static Object first(Object record, String path) { List<Object> values = at(record, path); return values.isEmpty() ? null : values.get(0); }
    private static String flatten(Object value) {
        if (value == null) return "";
        if (value instanceof Map) return flatten(new ArrayList<>(map(value).values()));
        if (value instanceof List) { StringBuilder text = new StringBuilder(); for (Object v : (List<?>) value) text.append(flatten(v)).append('\n'); return text.toString(); }
        return String.valueOf(value).toLowerCase(Locale.ROOT);
    }
    private static int compare(Object a, Object b) {
        if (a == null || b == null) return a == b ? 0 : a == null ? 1 : -1;
        if (a instanceof Number && b instanceof Number) return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()));
        return a.toString().compareTo(b.toString());
    }
    private Map<String, Object> copy(Map<String, Object> value) { return json.convertValue(value, new TypeReference<Map<String, Object>>() {}); }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap(); }
    private static Map<String, Object> requiredMap(Object value) { if (!(value instanceof Map)) throw error("invalid_query"); return map(value); }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> records(Object value) { return (List<Map<String, Object>>) value; }
    private static Set<String> set(String... values) { return new LinkedHashSet<>(Arrays.asList(values)); }
    private static Map<String, String> paths(String... entries) { Map<String, String> result = new LinkedHashMap<>(); for (int i = 0; i < entries.length; i += 2) result.put(entries[i], entries[i + 1]); return result; }
    private static Map<String, Object> row(Object... entries) { Map<String, Object> result = new LinkedHashMap<>(); for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]); return result; }
    private static void keys(Map<String, Object> input, Set<String> allowed) { if (!allowed.containsAll(input.keySet())) throw error("unsupported_filter"); }
    private static void choice(Object value, Set<String> choices) { if (!(value instanceof String) || !choices.contains(value)) throw error("unsupported_enum"); }
    private static String string(Object value, int max) { if (!(value instanceof String) || ((String) value).trim().isEmpty() || ((String) value).length() > max) throw error("invalid_query"); return (String) value; }
    private static List<?> list(Object value, int max) { if (!(value instanceof List) || ((List<?>) value).isEmpty() || ((List<?>) value).size() > max) throw error("invalid_query"); return (List<?>) value; }
    private static List<String> strings(Object value, int max, int length) { List<String> result = new ArrayList<>(); for (Object item : list(value, max)) result.add(string(item, length)); return result; }
    private static void integer(Object value, int min, int max) { if (!(value instanceof Integer) || (Integer) value < min || (Integer) value > max) throw error("invalid_query"); }
    private static LocalDate date(Object value) { try { return LocalDate.parse(string(value, 10)); } catch (RuntimeException e) { throw error("invalid_query"); } }
    private static OffsetDateTime instant(Object value) { try { return OffsetDateTime.parse(string(value, 64)); } catch (RuntimeException e) { throw error("invalid_query"); } }
    private static QueryException error(String code) { return new QueryException(code, set("history_unavailable", "invalid_cursor").contains(code) ? 409 : 422); }
}
