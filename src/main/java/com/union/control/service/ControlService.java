package com.union.control.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class ControlService {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:@-]{1,64}");
    private static final Pattern MEMORY_PATH =
            Pattern.compile("[A-Za-z0-9._:@+-]+(?:/[A-Za-z0-9._:@+-]+)*");
    private static final List<String> CLIENT_CONFIG_FIELDS = Arrays.asList(
            "agent", "agentName", "skill", "skillId", "memoryNamespace",
            "model", "provider", "userId");
    private static final String META_SELECT =
            "SELECT c.conversation_id AS conversationId,c.title,c.status," +
            "DATE_FORMAT(c.created_at,'%Y-%m-%dT%H:%i:%s') AS createdAt," +
            "DATE_FORMAT(c.updated_at,'%Y-%m-%dT%H:%i:%s') AS updatedAt," +
            "(SELECT e.status FROM ai_agent_execution e " +
            "WHERE e.conversation_id=c.conversation_id AND e.user_id=c.user_id " +
            "AND e.parent_execution_id IS NULL AND e.delete_flag=1 " +
            "ORDER BY e.created_at DESC,e.id DESC LIMIT 1) AS executionStatus " +
            "FROM ai_conversation c";
    private static final String EXECUTION_SELECT =
            "SELECT e.id,e.run_id AS runId,e.conversation_id AS conversationId," +
            "e.parent_execution_id AS parentExecutionId,p.run_id AS parentRunId," +
            "e.agent_name AS agentName,e.delegation_tool_call_id AS delegationToolCallId," +
            "e.task,e.status,e.error_code AS errorCode," +
            "DATE_FORMAT(e.finished_at,'%Y-%m-%dT%H:%i:%s') AS finishedAt," +
            "DATE_FORMAT(e.created_at,'%Y-%m-%dT%H:%i:%s') AS createdAt " +
            "FROM ai_agent_execution e LEFT JOIN ai_agent_execution p ON p.id=e.parent_execution_id";
    private static final Set<String> EXECUTION_STATUSES = new HashSet<>(Arrays.asList(
            "running", "cancel_requested", "completed", "failed", "cancelled"));
    private static final Set<String> TERMINAL_STATUSES = new HashSet<>(Arrays.asList(
            "completed", "failed", "cancelled"));
    private static final Set<String> MESSAGE_ROLES = new HashSet<>(Arrays.asList(
            "user", "assistant", "tool", "system", "developer", "reasoning"));

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    @Value("${agent.execution-max-age-seconds:900}")
    private int executionMaxAgeSeconds;

    @Value("${agent.cancel-request-max-age-seconds:30}")
    private int cancelRequestMaxAgeSeconds;

    public ControlService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @EventListener(ContextRefreshedEvent.class)
    @Scheduled(fixedDelayString = "${agent.execution-cleanup-interval-ms:30000}")
    @Transactional
    public void cleanupStaleExecutions() {
        jdbc.update("UPDATE ai_agent_execution e JOIN ai_agent_execution r " +
                        "ON e.parent_execution_id=r.id SET e.status='cancelled'," +
                        "e.error_code=CASE WHEN r.status='cancel_requested' THEN 'cancel_timeout' " +
                        "ELSE 'execution_timeout' END,e.finished_at=CURRENT_TIMESTAMP," +
                        "e.updated_at=CURRENT_TIMESTAMP WHERE r.parent_execution_id IS NULL " +
                        "AND r.delete_flag=1 AND e.delete_flag=1 " +
                        "AND e.status IN ('running','cancel_requested') AND " +
                        "((r.status='running' AND r.created_at<DATE_SUB(CURRENT_TIMESTAMP,INTERVAL ? SECOND)) " +
                        "OR (r.status='cancel_requested' AND r.updated_at<DATE_SUB(CURRENT_TIMESTAMP,INTERVAL ? SECOND)))",
                executionMaxAgeSeconds, cancelRequestMaxAgeSeconds);
        jdbc.update("UPDATE ai_agent_execution SET status='cancelled'," +
                        "error_code=CASE WHEN status='cancel_requested' THEN 'cancel_timeout' " +
                        "ELSE 'execution_timeout' END,finished_at=CURRENT_TIMESTAMP," +
                        "updated_at=CURRENT_TIMESTAMP WHERE parent_execution_id IS NULL " +
                        "AND delete_flag=1 AND ((status='running' " +
                        "AND created_at<DATE_SUB(CURRENT_TIMESTAMP,INTERVAL ? SECOND)) " +
                        "OR (status='cancel_requested' " +
                        "AND updated_at<DATE_SUB(CURRENT_TIMESTAMP,INTERVAL ? SECOND)))",
                executionMaxAgeSeconds, cancelRequestMaxAgeSeconds);
    }

    public Map<String, Object> userInfo(String cookieHeader) {
        Identity identity = identity(cookieHeader);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("casSessionId", LocalAuth.CAS_SESSION_ID);
        user.put("userId", identity.userId);
        user.put("permissions", new ArrayList<String>());
        return ok(user);
    }

    public Map<String, Object> conversations(String cookieHeader, int limit) {
        Identity identity = identity(cookieHeader);
        requireRange(limit, 1, 100, "limit");
        String sql = META_SELECT + " WHERE c.user_id=? AND c.delete_flag=1 " +
                "AND c.status='active' " +
                "ORDER BY c.created_at DESC,c.id DESC LIMIT ?";
        List<Map<String, Object>> rows =
                jdbc.query(sql, (rs, rowNum) -> metadata(rs), identity.userId, limit);
        return ok(rows);
    }

    public Map<String, Object> conversation(String cookieHeader, String conversationId) {
        Identity identity = identity(cookieHeader);
        requireId(conversationId);
        requireOwned(identity.userId, conversationId, false);
        Map<String, Object> result = loadConversation(identity.userId, conversationId);
        result.put("messages", loadBrowserMessages(identity.userId, conversationId));
        result.put("executions", loadExecutions(identity.userId, conversationId));
        return ok(result);
    }

    public Map<String, Object> conversationMessages(
            String cookieHeader, Map<String, Object> payload) {
        Identity identity = identity(cookieHeader);
        String conversationId = text(payload, "conversationId", 64, true);
        requireId(conversationId);
        requireOwned(identity.userId, conversationId, false);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("messages", loadRootMessages(identity.userId, conversationId));
        return response;
    }

    public Map<String, Object> rename(String cookieHeader, Map<String, Object> payload) {
        Identity identity = identity(cookieHeader);
        String conversationId = text(payload, "conversationId", 64, true);
        String title = text(payload, "title", 255, true);
        requireId(conversationId);
        requireOwned(identity.userId, conversationId, false);
        jdbc.update("UPDATE ai_conversation SET title=?,updated_at=CURRENT_TIMESTAMP " +
                        "WHERE conversation_id=? AND user_id=? AND delete_flag=1",
                title, conversationId, identity.userId);
        return ok(loadConversation(identity.userId, conversationId));
    }

    @Transactional
    public Map<String, Object> deleteConversation(String cookieHeader, Map<String, Object> payload) {
        Identity identity = identity(cookieHeader);
        String conversationId = text(payload, "conversationId", 64, true);
        requireId(conversationId);
        requireOwned(identity.userId, conversationId, true);
        jdbc.update("UPDATE ai_conversation_message SET delete_flag=0,updated_at=CURRENT_TIMESTAMP " +
                        "WHERE conversation_id=? AND user_id=? AND delete_flag=1",
                conversationId, identity.userId);
        jdbc.update("UPDATE ai_agent_execution SET delete_flag=0,updated_at=CURRENT_TIMESTAMP " +
                        "WHERE conversation_id=? AND user_id=? AND delete_flag=1",
                conversationId, identity.userId);
        int updated = jdbc.update("UPDATE ai_conversation SET delete_flag=0,updated_at=CURRENT_TIMESTAMP " +
                        "WHERE conversation_id=? AND user_id=? AND delete_flag=1",
                conversationId, identity.userId);
        if (updated != 1) throw new NotFoundException("会话不存在");
        return ok(null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> claimAguiRun(String cookieHeader, byte[] payload) {
        Identity identity = identity(cookieHeader);
        Map<String, Object> input = parseObject(payload, "AG-UI 请求");
        String conversationId = text(input, "threadId", 64, true);
        String runId = text(input, "runId", 64, true);
        requireId(conversationId);
        requireExecutionToken(runId);
        String title = validateAguiInput(input);
        cleanupStaleExecutions();
        Map<String, Object> active = loadCurrentExecution(identity.userId);
        if (active != null) throw new ActiveExecutionException();
        try {
            jdbc.update("INSERT INTO ai_conversation " +
                            "(conversation_id,user_id,title,delete_flag) VALUES (?,?,?,1)",
                    conversationId, identity.userId, title);
        } catch (DuplicateKeyException ignored) {
            // Existing active conversation is expected on subsequent turns.
        }
        requireOwnedActive(identity.userId, conversationId);
        try {
            jdbc.update("INSERT INTO ai_agent_execution " +
                            "(run_id,conversation_id,user_id,parent_execution_id,agent_name,status,delete_flag) " +
                            "VALUES (?,?,?,NULL,'UnionCoordinatorAgent','running',1)",
                    runId, conversationId, identity.userId);
        } catch (DuplicateKeyException error) {
            active = loadCurrentExecution(identity.userId);
            if (active != null) throw new ActiveExecutionException();
            throw error;
        }
        return ok(loadExecution(identity.userId, conversationId, runId, false));
    }

    @Transactional
    public Map<String, Object> cancelExecution(
            String cookieHeader, Map<String, Object> payload) {
        String conversationId = text(payload, "conversationId", 64, true);
        String runId = text(payload, "runId", 64, true);
        return cancelExecution(cookieHeader, conversationId, runId, "cancelled");
    }

    @Transactional
    public Map<String, Object> cancelExecution(
            String cookieHeader, String conversationId, String runId, String reason) {
        Identity identity = identity(cookieHeader);
        Map<String, Object> root = loadExecution(
                identity.userId, conversationId, runId, true);
        if (root.get("parentExecutionId") != null)
            throw new IllegalArgumentException("runId 不是 root execution");
        requestCancellation(root, reason);
        return ok(publicExecution(loadExecution(
                identity.userId, conversationId, runId, false)));
    }

    @Transactional
    public void failExecution(
            String cookieHeader, String conversationId, String runId, String errorCode) {
        Identity identity = identity(cookieHeader);
        Map<String, Object> root = loadExecution(
                identity.userId, conversationId, runId, true);
        if (root.get("parentExecutionId") != null)
            throw new IllegalArgumentException("runId 不是 root execution");
        String status = "cancel_requested".equals(root.get("status")) ||
                "execution_timeout".equals(errorCode)
                ? "cancelled" : "failed";
        Object terminalError = status.equals("cancelled")
                ? (root.get("errorCode") == null ? "cancelled" : root.get("errorCode"))
                : errorCode;
        jdbc.update("UPDATE ai_agent_execution SET status=?,error_code=?," +
                        "finished_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP " +
                        "WHERE parent_execution_id=? AND delete_flag=1 " +
                        "AND status IN ('running','cancel_requested')",
                status, terminalError, root.get("id"));
        finishExecution(root, status, errorCode);
    }

    @Transactional
    public Map<String, Object> rootExecutionSelected(
            String cookieHeader, Map<String, Object> payload) {
        Identity identity = identity(cookieHeader);
        String conversationId = text(payload, "conversationId", 64, true);
        String runId = text(payload, "runId", 64, true);
        String agentName = text(payload, "agentName", 128, true);
        Map<String, Object> root = loadExecution(
                identity.userId, conversationId, runId, true);
        if (root.get("parentExecutionId") != null)
            throw new IllegalArgumentException("runId 不是 root execution");
        String current = String.valueOf(root.get("agentName"));
        if (current.equals(agentName)) return ok(publicExecution(root));
        if (!"UnionCoordinatorAgent".equals(current) ||
                !"running".equals(root.get("status")))
            throw new StaleExecutionException();
        int updated = jdbc.update("UPDATE ai_agent_execution SET agent_name=?," +
                        "updated_at=CURRENT_TIMESTAMP WHERE id=? AND agent_name=? " +
                        "AND status='running' AND delete_flag=1",
                agentName, root.get("id"), current);
        if (updated != 1) throw new StaleExecutionException();
        return ok(publicExecution(loadExecution(
                identity.userId, conversationId, runId, false)));
    }

    @Transactional
    public Map<String, Object> executionStarted(
            String cookieHeader, Map<String, Object> payload) {
        Identity identity = identity(cookieHeader);
        String conversationId = text(payload, "conversationId", 64, true);
        String runId = text(payload, "runId", 64, true);
        String parentRunId = text(payload, "parentRunId", 64, true);
        String agentName = text(payload, "agentName", 128, true);
        String delegationCallId = text(payload, "delegationToolCallId", 128, true);
        String task = text(payload, "task", 1000, true);
        requireId(conversationId);
        requireExecutionToken(runId);
        requireExecutionToken(parentRunId);
        requireOwnedActive(identity.userId, conversationId);
        Map<String, Object> parent = loadExecution(
                identity.userId, conversationId, parentRunId, true);
        String parentStatus = String.valueOf(parent.get("status"));
        if (!"running".equals(parentStatus))
            throw new StaleExecutionException();
        try {
            jdbc.update("INSERT INTO ai_agent_execution " +
                            "(run_id,conversation_id,user_id,parent_execution_id,agent_name," +
                            "delegation_tool_call_id,task,status,delete_flag) VALUES (?,?,?,?,?,?,?,'running',1)",
                    runId, conversationId, identity.userId, parent.get("id"), agentName,
                    delegationCallId, task);
        } catch (DuplicateKeyException error) {
            Map<String, Object> existing = loadExecution(
                    identity.userId, conversationId, runId, false);
            if (!sameExecution(existing, parentRunId, agentName, delegationCallId, task))
                throw new IllegalArgumentException("child execution 幂等冲突");
        }
        return ok(loadExecution(identity.userId, conversationId, runId, false));
    }

    @Transactional
    public Map<String, Object> executionFinished(
            String cookieHeader, Map<String, Object> payload) {
        Identity identity = identity(cookieHeader);
        String conversationId = text(payload, "conversationId", 64, true);
        String runId = text(payload, "runId", 64, true);
        String status = executionStatus(payload, "status", true);
        String errorCode = optionalText(payload, "errorCode", 64);
        Map<String, Object> execution = loadExecution(
                identity.userId, conversationId, runId, true);
        if (execution.get("parentExecutionId") == null)
            throw new IllegalArgumentException("root execution 只能由 completeRun 完成");
        finishExecution(execution, status, errorCode);
        return ok(null);
    }

    @Transactional
    public Map<String, Object> completeRun(String cookieHeader, Map<String, Object> payload) {
        Identity identity = identity(cookieHeader);
        String conversationId = text(payload, "conversationId", 64, true);
        String rootRunId = text(payload, "runId", 64, true);
        String rootStatus = executionStatus(payload, "status", true);
        String rootErrorCode = optionalText(payload, "errorCode", 64);
        requireId(conversationId);
        requireExecutionToken(rootRunId);
        Map<String, Object> root = loadExecution(
                identity.userId, conversationId, rootRunId, true);
        if (root.get("parentExecutionId") != null)
            throw new IllegalArgumentException("runId 不是 root execution");
        String currentRootStatus = String.valueOf(root.get("status"));
        boolean replayOnly = TERMINAL_STATUSES.contains(currentRootStatus);
        boolean cancellationWins = "cancel_requested".equals(currentRootStatus);
        if (replayOnly && (!currentRootStatus.equals(rootStatus) ||
                !same((String) root.get("errorCode"), rootErrorCode)))
            return ok(null);
        if (cancellationWins && !"cancelled".equals(rootStatus)) {
            Object cancellationError = root.get("errorCode") == null
                    ? "cancelled" : root.get("errorCode");
            jdbc.update("UPDATE ai_agent_execution SET status='cancelled',error_code=?," +
                            "finished_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP " +
                            "WHERE parent_execution_id=? AND delete_flag=1 " +
                            "AND status IN ('running','cancel_requested')",
                    cancellationError, root.get("id"));
            finishExecution(root, "cancelled", String.valueOf(cancellationError));
            return ok(null);
        }

        Object rawExecutions = payload.get("executions");
        if (!(rawExecutions instanceof List) || ((List<?>) rawExecutions).isEmpty() ||
                ((List<?>) rawExecutions).size() > 100)
            throw new IllegalArgumentException("executions 非法");
        Map<String, Map<String, Object>> requested = validateExecutionTree(
                (List<?>) rawExecutions,
                rootRunId,
                String.valueOf(root.get("agentName")));
        if (cancellationWins) {
            String cancellationError = root.get("errorCode") == null
                    ? "cancelled" : String.valueOf(root.get("errorCode"));
            for (Map<String, Object> value : requested.values()) {
                value.put("status", "cancelled");
                value.put("errorCode", cancellationError);
            }
        }
        Map<String, Long> executionIds = new HashMap<>();
        executionIds.put(rootRunId, number(root.get("id")));
        Map<String, Map<String, Object>> pending = new LinkedHashMap<>(requested);
        pending.remove(rootRunId);
        while (!pending.isEmpty()) {
            List<String> processed = new ArrayList<>();
            for (Map<String, Object> value : pending.values()) {
                String parentRunId = String.valueOf(value.get("parentRunId"));
                Long parentId = executionIds.get(parentRunId);
                if (parentId == null) continue;
                String runId = String.valueOf(value.get("runId"));
                Map<String, Object> existing = findExecution(
                        identity.userId, conversationId, runId, true);
                if (existing == null) {
                    if (replayOnly)
                        throw new IllegalArgumentException("execution 终态重放冲突");
                    jdbc.update("INSERT INTO ai_agent_execution " +
                                    "(run_id,conversation_id,user_id,parent_execution_id,agent_name," +
                                    "delegation_tool_call_id,task,status,delete_flag) VALUES (?,?,?,?,?,?,?,'running',1)",
                            runId, conversationId, identity.userId, parentId,
                            value.get("agentName"), value.get("delegationToolCallId"), value.get("task"));
                    existing = loadExecution(identity.userId, conversationId, runId, true);
                } else if (!sameExecution(existing, parentRunId,
                        String.valueOf(value.get("agentName")),
                        (String) value.get("delegationToolCallId"),
                        (String) value.get("task"))) {
                    throw new IllegalArgumentException("execution 幂等冲突");
                }
                executionIds.put(runId, number(existing.get("id")));
                processed.add(runId);
            }
            if (processed.isEmpty())
                throw new IllegalArgumentException("execution tree 无法持久化");
            for (String runId : processed) pending.remove(runId);
        }

        Object rawMessages = payload.get("messages");
        if (!(rawMessages instanceof List) || ((List<?>) rawMessages).size() > 1000)
            throw new IllegalArgumentException("messages 非法");
        Long current = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sequence_no),0) FROM ai_conversation_message " +
                        "WHERE conversation_id=?",
                Long.class, conversationId);
        long sequence = current == null ? 0 : current;
        for (Object raw : (List<?>) rawMessages) {
            if (!(raw instanceof Map)) throw new IllegalArgumentException("AG-UI message 必须是对象");
            Map<?, ?> envelope = (Map<?, ?>) raw;
            String messageRunId = text(envelope, "runId", 64, true);
            Object rawMessage = envelope.get("message");
            if (!(rawMessage instanceof Map)) throw new IllegalArgumentException("message 非法");
            @SuppressWarnings("unchecked") Map<String, Object> message =
                    (Map<String, Object>) rawMessage;
            String messageId = text(message, "id", 128, true);
            String role = text(message, "role", 32, true);
            if (!MESSAGE_ROLES.contains(role))
                throw new IllegalArgumentException("AG-UI message role 非法");
            Long executionId = executionIds.get(messageRunId);
            if (executionId == null)
                throw new IllegalArgumentException("message execution 不存在");
            Map<String, Object> messagePayload = new LinkedHashMap<>(message);
            messagePayload.remove("id");
            messagePayload.remove("role");
            if (replayOnly) {
                requireSameMessage(
                        conversationId, messageId, executionId, role, messagePayload);
                continue;
            }
            try {
                jdbc.update("INSERT INTO ai_conversation_message " +
                                "(conversation_id,user_id,message_id,agent_execution_id,role,sequence_no,payload,delete_flag) " +
                                "VALUES (?,?,?,?,?,?,?,1)",
                        conversationId, identity.userId, messageId, executionId, role,
                        ++sequence, json.writeValueAsString(messagePayload));
            } catch (DuplicateKeyException ignored) {
                --sequence;
                Map<String, Object> existing;
                try {
                    existing = jdbc.queryForMap(
                            "SELECT agent_execution_id AS executionId,role,payload FROM ai_conversation_message " +
                                    "WHERE conversation_id=? AND message_id=?",
                            conversationId, messageId);
                    if (number(existing.get("executionId")) != executionId ||
                            !role.equals(existing.get("role")) ||
                            !json.readTree(String.valueOf(existing.get("payload")))
                                    .equals(json.valueToTree(messagePayload))) {
                        throw new IllegalArgumentException("messageId 已被不同消息使用");
                    }
                } catch (IllegalArgumentException error) {
                    throw error;
                } catch (Exception error) {
                    throw new IllegalArgumentException("messageId 幂等校验失败");
                }
            } catch (Exception error) {
                throw new IllegalArgumentException("AG-UI message 不是有效 JSON");
            }
        }
        for (Map<String, Object> value : requested.values()) {
            Map<String, Object> execution = loadExecution(
                    identity.userId, conversationId, String.valueOf(value.get("runId")), true);
            finishExecution(execution, String.valueOf(value.get("status")),
                    (String) value.get("errorCode"));
        }
        if (!cancellationWins &&
                (!rootStatus.equals(requested.get(rootRunId).get("status")) ||
                !same(rootErrorCode, (String) requested.get(rootRunId).get("errorCode"))))
            throw new IllegalArgumentException("root execution 状态不一致");
        jdbc.update("UPDATE ai_conversation SET updated_at=CURRENT_TIMESTAMP " +
                        "WHERE conversation_id=? AND user_id=? AND delete_flag=1",
                conversationId, identity.userId);
        return ok(null);
    }

    public Map<String, Object> memoryRead(String cookieHeader, Map<String, Object> payload) {
        Identity identity = identity(cookieHeader);
        String path = memoryPath(identity.userId, payload, "path");
        int maxChars = integer(payload, "maxChars", 1, 65536);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT content,version,last_operation_id AS operationId FROM ai_memory_file " +
                        "WHERE user_id=? AND path=? AND delete_flag=1",
                identity.userId, path);
        Map<String, Object> response = success();
        if (rows.isEmpty()) {
            response.put("file", null);
            return response;
        }
        Map<String, Object> row = rows.get(0);
        String content = String.valueOf(row.get("content"));
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("content", content.substring(0, Math.min(content.length(), maxChars)));
        file.put("version", String.valueOf(row.get("version")));
        file.put("operationId", row.get("operationId"));
        file.put("truncated", content.length() > maxChars);
        response.put("file", file);
        return response;
    }

    public Map<String, Object> memoryList(String cookieHeader, Map<String, Object> payload) {
        Identity identity = identity(cookieHeader);
        String prefix = memoryPrefix(identity.userId, payload);
        int limit = integer(payload, "limit", 1, 1000);
        List<String> paths = jdbc.query(
                "SELECT path FROM ai_memory_file WHERE user_id=? AND delete_flag=1 " +
                        "AND path LIKE ? ORDER BY path LIMIT ?",
                (rs, rowNum) -> rs.getString("path"), identity.userId, likePrefix(prefix), limit);
        Map<String, Object> response = success();
        response.put("paths", paths);
        return response;
    }

    public Map<String, Object> memoryOperation(String cookieHeader, Map<String, Object> payload) {
        Identity identity = identity(cookieHeader);
        String operationId = text(payload, "operationId", 128, true);
        String fingerprint = text(payload, "fingerprint", 128, true);
        Map<String, Object> response = success();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT fingerprint,result_version AS resultVersion,existed FROM ai_memory_operation " +
                        "WHERE user_id=? AND operation_id=?",
                identity.userId, operationId);
        if (rows.isEmpty()) {
            response.put("mutation", null);
        } else if (!fingerprint.equals(String.valueOf(rows.get(0).get("fingerprint")))) {
            return memoryError("operation_conflict", "operation id 已被不同参数使用");
        } else {
            response.put("mutation", mutation(rows.get(0).get("resultVersion"), true,
                    number(rows.get(0).get("existed")) != 0));
        }
        return response;
    }

    @Transactional
    public Map<String, Object> memoryWrite(String cookieHeader, Map<String, Object> payload) {
        Identity identity = identity(cookieHeader);
        String path = memoryPath(identity.userId, payload, "path");
        String content = text(payload, "content", 65536, true);
        String expected = optionalText(payload, "expectedVersion", 32);
        Operation operation = operation(payload);
        Map<String, Object> replay = replay(identity.userId, operation);
        if (replay != null) return replay;
        List<Map<String, Object>> rows = memoryRow(identity.userId, path);
        String currentVersion = rows.isEmpty() ? null : String.valueOf(rows.get(0).get("version"));
        if (!same(currentVersion, expected))
            return memoryError("version_conflict", "memory version 已变化");
        boolean existed = !rows.isEmpty();
        Long prior = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version),0) FROM ai_memory_file WHERE user_id=? AND path=?",
                Long.class, identity.userId, path);
        long version = (prior == null ? 0 : prior) + 1;
        if (existed) {
            jdbc.update("UPDATE ai_memory_file SET content=?,version=?,last_operation_id=?,updated_at=CURRENT_TIMESTAMP " +
                            "WHERE id=? AND delete_flag=1",
                    content, version, operation == null ? null : operation.id, rows.get(0).get("id"));
        } else {
            jdbc.update("INSERT INTO ai_memory_file " +
                            "(user_id,path,content,version,last_operation_id,delete_flag) VALUES (?,?,?,?,?,1)",
                    identity.userId, path, content, version, operation == null ? null : operation.id);
        }
        saveReceipt(identity.userId, operation, version, existed);
        Map<String, Object> response = success();
        response.put("mutation", mutation(version, false, existed));
        return response;
    }

    @Transactional
    public Map<String, Object> memoryDelete(String cookieHeader, Map<String, Object> payload) {
        Identity identity = identity(cookieHeader);
        String path = memoryPath(identity.userId, payload, "path");
        String expected = optionalText(payload, "expectedVersion", 32);
        Operation operation = operation(payload);
        Map<String, Object> replay = replay(identity.userId, operation);
        if (replay != null) return replay;
        List<Map<String, Object>> rows = memoryRow(identity.userId, path);
        String currentVersion = rows.isEmpty() ? null : String.valueOf(rows.get(0).get("version"));
        if (!same(currentVersion, expected))
            return memoryError("version_conflict", "memory version 已变化");
        boolean existed = !rows.isEmpty();
        if (existed) {
            jdbc.update("UPDATE ai_memory_file SET delete_flag=0,updated_at=CURRENT_TIMESTAMP " +
                    "WHERE id=? AND delete_flag=1", rows.get(0).get("id"));
        }
        saveReceipt(identity.userId, operation, null, existed);
        Map<String, Object> response = success();
        response.put("mutation", mutation(null, false, existed));
        return response;
    }

    public Map<String, Object> memorySearch(String cookieHeader, Map<String, Object> payload) {
        Identity identity = identity(cookieHeader);
        String prefix = memoryPrefix(identity.userId, payload);
        String query = text(payload, "query", 512, true);
        int limit = integer(payload, "limit", 1, 100);
        int maxFiles = integer(payload, "maxFiles", 1, 1000);
        int maxChars = integer(payload, "maxChars", 1, 100000);
        int maxFileChars = integer(payload, "maxFileChars", 1, 65536);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT path,content FROM ai_memory_file WHERE user_id=? AND delete_flag=1 " +
                        "AND path LIKE ? ORDER BY path LIMIT ?",
                identity.userId, likePrefix(prefix), maxFiles + 1);
        boolean truncated = rows.size() > maxFiles;
        List<Map<String, Object>> matches = new ArrayList<>();
        int remaining = maxChars;
        int scanned = Math.min(rows.size(), maxFiles);
        String lowerQuery = query.toLowerCase();
        for (int index = 0; index < scanned && matches.size() < limit && remaining > 0; index++) {
            Map<String, Object> row = rows.get(index);
            String path = String.valueOf(row.get("path"));
            String content = String.valueOf(row.get("content"));
            String bounded = content.substring(0, Math.min(content.length(), maxFileChars));
            if (!path.toLowerCase().contains(lowerQuery) && !bounded.toLowerCase().contains(lowerQuery))
                continue;
            String snippet = bounded.substring(0, Math.min(bounded.length(), remaining));
            Map<String, Object> match = new LinkedHashMap<>();
            match.put("path", path);
            match.put("snippet", snippet);
            match.put("score", 1.0d);
            matches.add(match);
            remaining -= path.length() + snippet.length();
            if (content.length() > maxFileChars) truncated = true;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matches", matches);
        result.put("scanned", scanned);
        result.put("truncated", truncated);
        Map<String, Object> response = success();
        response.put("result", result);
        return response;
    }

    private List<Map<String, Object>> memoryRow(String userId, String path) {
        return jdbc.queryForList("SELECT id,version FROM ai_memory_file " +
                        "WHERE user_id=? AND path=? AND delete_flag=1 FOR UPDATE",
                userId, path);
    }

    private Map<String, Object> replay(String userId, Operation operation) {
        if (operation == null) return null;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT fingerprint,result_version AS resultVersion,existed FROM ai_memory_operation " +
                        "WHERE user_id=? AND operation_id=? FOR UPDATE",
                userId, operation.id);
        if (rows.isEmpty()) return null;
        if (!operation.fingerprint.equals(String.valueOf(rows.get(0).get("fingerprint"))))
            return memoryError("operation_conflict", "operation id 已被不同参数使用");
        Map<String, Object> response = success();
        response.put("mutation", mutation(rows.get(0).get("resultVersion"), true,
                number(rows.get(0).get("existed")) != 0));
        return response;
    }

    private void saveReceipt(String userId, Operation operation, Long version, boolean existed) {
        if (operation == null) return;
        jdbc.update("INSERT INTO ai_memory_operation " +
                        "(user_id,operation_id,fingerprint,result_version,existed,delete_flag) " +
                        "VALUES (?,?,?,?,?,1)",
                userId, operation.id, operation.fingerprint, version, existed ? 1 : 0);
    }

    private Operation operation(Map<String, Object> payload) {
        Object raw = payload.get("operation");
        if (raw == null) return null;
        if (!(raw instanceof Map)) throw new IllegalArgumentException("operation 非法");
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) raw;
        return new Operation(text(value, "id", 128, true),
                text(value, "fingerprint", 128, true));
    }

    private String memoryPath(String userId, Map<String, Object> payload, String key) {
        String path = text(payload, key, 512, true);
        if (!MEMORY_PATH.matcher(path).matches() || path.contains("..") ||
                !path.startsWith(userId + "/personal/"))
            throw new IllegalArgumentException("memory path 非法");
        return path;
    }

    private String memoryPrefix(String userId, Map<String, Object> payload) {
        String prefix = optionalText(payload, "prefix", 512);
        if (prefix == null) prefix = "";
        if (prefix.isEmpty()) prefix = userId + "/personal/";
        if (!prefix.startsWith(userId + "/personal/") || prefix.contains(".."))
            throw new IllegalArgumentException("memory prefix 非法");
        return prefix;
    }

    private List<Object> loadRootMessages(String userId, String conversationId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT m.message_id AS messageId,m.role,m.payload " +
                        "FROM ai_conversation_message m " +
                        "JOIN ai_agent_execution e ON e.id=m.agent_execution_id " +
                        "WHERE m.conversation_id=? AND m.user_id=? AND m.delete_flag=1 " +
                        "AND e.delete_flag=1 AND e.parent_execution_id IS NULL " +
                        "ORDER BY m.sequence_no",
                conversationId, userId);
        List<Object> messages = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            messages.add(message(row));
        }
        return messages;
    }

    private List<Object> loadBrowserMessages(String userId, String conversationId) {
        List<Map<String, Object>> executions = loadExecutionRows(userId, conversationId);
        Map<String, Map<String, Object>> byRun = new LinkedHashMap<>();
        for (Map<String, Object> execution : executions)
            byRun.put(String.valueOf(execution.get("runId")), execution);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT m.message_id AS messageId,m.role,m.payload,e.run_id AS runId," +
                        "e.parent_execution_id AS parentExecutionId " +
                        "FROM ai_conversation_message m " +
                        "JOIN ai_agent_execution e ON e.id=m.agent_execution_id " +
                        "WHERE m.conversation_id=? AND m.user_id=? AND m.delete_flag=1 " +
                        "AND e.delete_flag=1 ORDER BY m.sequence_no",
                conversationId, userId);
        List<Object> result = new ArrayList<>();
        Map<String, List<Object>> childMessages = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            if (row.get("parentExecutionId") == null) {
                result.add(message(row));
                continue;
            }
            String runId = String.valueOf(row.get("runId"));
            List<Object> messages = childMessages.get(runId);
            if (messages == null) {
                messages = new ArrayList<>();
                childMessages.put(runId, messages);
                result.add(activity(byRun.get(runId), messages));
            }
            messages.add(message(row));
        }
        for (Map<String, Object> execution : executions) {
            if (execution.get("parentExecutionId") == null) continue;
            String runId = String.valueOf(execution.get("runId"));
            if (!childMessages.containsKey(runId))
                result.add(activity(execution, new ArrayList<>()));
        }
        return result;
    }

    private Map<String, Object> message(Map<String, Object> row) {
        try {
            Object parsed = json.readValue(String.valueOf(row.get("payload")),
                    new TypeReference<Object>() {});
            if (!(parsed instanceof Map)) throw new DataCorruptionException();
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("id", row.get("messageId"));
            message.put("role", row.get("role"));
            @SuppressWarnings("unchecked") Map<String, Object> payload =
                    (Map<String, Object>) parsed;
            message.putAll(payload);
            return message;
        } catch (DataCorruptionException error) {
            throw error;
        } catch (Exception error) {
            throw new DataCorruptionException();
        }
    }

    private static Map<String, Object> activity(
            Map<String, Object> execution, List<Object> messages) {
        if (execution == null) throw new DataCorruptionException();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("runId", execution.get("runId"));
        content.put("parentRunId", execution.get("parentRunId"));
        content.put("agentName", execution.get("agentName"));
        content.put("delegationToolCallId", execution.get("delegationToolCallId"));
        content.put("task", execution.get("task"));
        content.put("status", execution.get("status"));
        content.put("messages", messages);
        content.put("errorCode", execution.get("errorCode"));
        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("id", "subagent:" + execution.get("runId"));
        activity.put("role", "activity");
        activity.put("activityType", "subagent_execution");
        activity.put("content", content);
        return activity;
    }

    private Map<String, Object> loadConversation(String userId, String conversationId) {
        try {
            return jdbc.queryForMap(META_SELECT +
                    " WHERE c.conversation_id=? AND c.user_id=? AND c.delete_flag=1",
                    conversationId, userId);
        } catch (EmptyResultDataAccessException error) {
            throw new NotFoundException("会话不存在");
        }
    }

    private Map<String, Object> loadCurrentExecution(String userId) {
        Map<String, Object> row = loadCurrentRoot(userId, false);
        if (row == null) return null;
        Map<String, Object> root = publicExecution(row);
        List<Map<String, Object>> children = new ArrayList<>();
        for (Map<String, Object> childRow : loadExecutionRows(
                userId, String.valueOf(root.get("conversationId")))) {
            if (root.get("runId").equals(childRow.get("parentRunId")))
                children.add(publicExecution(childRow));
        }
        root.put("children", children);
        return root;
    }

    private Map<String, Object> loadCurrentRoot(String userId, boolean lock) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                EXECUTION_SELECT + " WHERE e.user_id=? AND e.parent_execution_id IS NULL " +
                        "AND e.status IN ('running','cancel_requested') AND e.delete_flag=1 " +
                        "LIMIT 1" + (lock ? " FOR UPDATE" : ""),
                userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Map<String, Object>> loadExecutionRows(String userId, String conversationId) {
        return jdbc.queryForList(EXECUTION_SELECT +
                        " WHERE e.user_id=? AND e.conversation_id=? AND e.delete_flag=1 " +
                        "ORDER BY e.created_at,e.id",
                userId, conversationId);
    }

    private List<Map<String, Object>> loadExecutions(String userId, String conversationId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : loadExecutionRows(userId, conversationId))
            result.add(publicExecution(row));
        return result;
    }

    private static Map<String, Object> publicExecution(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.remove("id");
        result.remove("parentExecutionId");
        return result;
    }

    private Map<String, Object> findExecution(
            String userId, String conversationId, String runId, boolean lock) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                EXECUTION_SELECT + " WHERE e.user_id=? AND e.conversation_id=? " +
                        "AND e.run_id=? AND e.delete_flag=1" + (lock ? " FOR UPDATE" : ""),
                userId, conversationId, runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> loadExecution(
            String userId, String conversationId, String runId, boolean lock) {
        requireId(conversationId);
        requireExecutionToken(runId);
        Map<String, Object> execution = findExecution(userId, conversationId, runId, lock);
        if (execution == null) throw new StaleExecutionException();
        return execution;
    }

    private static boolean sameExecution(
            Map<String, Object> existing, String parentRunId, String agentName,
            String delegationCallId, String task) {
        return same(parentRunId, (String) existing.get("parentRunId")) &&
                same(agentName, (String) existing.get("agentName")) &&
                same(delegationCallId, (String) existing.get("delegationToolCallId")) &&
                same(task, (String) existing.get("task"));
    }

    private void requestCancellation(Map<String, Object> root, String reason) {
        String current = String.valueOf(root.get("status"));
        if (TERMINAL_STATUSES.contains(current)) return;
        if (!"running".equals(current) && !"cancel_requested".equals(current))
            throw new StaleExecutionException();
        jdbc.update("UPDATE ai_agent_execution SET status='cancel_requested'," +
                        "error_code=COALESCE(error_code,?),updated_at=CURRENT_TIMESTAMP " +
                        "WHERE parent_execution_id=? AND status='running' AND delete_flag=1",
                reason, root.get("id"));
        jdbc.update("UPDATE ai_agent_execution SET status='cancel_requested'," +
                        "error_code=COALESCE(error_code,?),updated_at=CURRENT_TIMESTAMP " +
                        "WHERE id=? AND status='running' AND delete_flag=1",
                reason, root.get("id"));
    }

    private void finishExecution(
            Map<String, Object> execution, String status, String errorCode) {
        if (!TERMINAL_STATUSES.contains(status))
            throw new IllegalArgumentException("execution 终态非法");
        String current = String.valueOf(execution.get("status"));
        if (TERMINAL_STATUSES.contains(current)) return;
        if (!"running".equals(current) && !"cancel_requested".equals(current))
            throw new StaleExecutionException();
        if ("cancel_requested".equals(current)) {
            status = "cancelled";
            errorCode = (String) execution.get("errorCode");
            if (errorCode == null) errorCode = "cancelled";
        }
        int updated = jdbc.update("UPDATE ai_agent_execution SET status=?,error_code=?," +
                        "finished_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP " +
                        "WHERE id=? AND delete_flag=1 AND status IN ('running','cancel_requested')",
                status, errorCode, execution.get("id"));
        if (updated != 1) throw new StaleExecutionException();
    }

    private void requireSameMessage(
            String conversationId, String messageId, Long executionId,
            String role, Map<String, Object> messagePayload) {
        try {
            Map<String, Object> existing = jdbc.queryForMap(
                    "SELECT agent_execution_id AS executionId,role,payload " +
                            "FROM ai_conversation_message " +
                            "WHERE conversation_id=? AND message_id=?",
                    conversationId, messageId);
            if (number(existing.get("executionId")) != executionId ||
                    !role.equals(existing.get("role")) ||
                    !json.readTree(String.valueOf(existing.get("payload")))
                            .equals(json.valueToTree(messagePayload)))
                throw new IllegalArgumentException("messageId 已被不同消息使用");
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("messageId 幂等校验失败");
        }
    }

    private Map<String, Map<String, Object>> validateExecutionTree(
            List<?> values, String rootRunId, String rootAgentName) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Object raw : values) {
            if (!(raw instanceof Map)) throw new IllegalArgumentException("execution 非法");
            Map<?, ?> value = (Map<?, ?>) raw;
            String runId = text(value, "runId", 64, true);
            requireExecutionToken(runId);
            String parentRunId = text(value, "parentRunId", 64, false);
            String agentName = text(value, "agentName", 128, true);
            String delegationCallId = text(value, "delegationToolCallId", 128, false);
            String task = text(value, "task", 1000, false);
            String status = executionStatus(value, "status", true);
            String errorCode = text(value, "errorCode", 64, false);
            if (rootRunId.equals(runId)) {
                if (parentRunId != null || delegationCallId != null || task != null ||
                        !rootAgentName.equals(agentName))
                    throw new IllegalArgumentException("root execution metadata 非法");
            } else if (parentRunId == null || delegationCallId == null || task == null) {
                throw new IllegalArgumentException("child execution metadata 非法");
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("runId", runId);
            normalized.put("parentRunId", parentRunId);
            normalized.put("agentName", agentName);
            normalized.put("delegationToolCallId", delegationCallId);
            normalized.put("task", task);
            normalized.put("status", status);
            normalized.put("errorCode", errorCode);
            if (result.put(runId, normalized) != null)
                throw new IllegalArgumentException("execution runId 重复");
        }
        if (!result.containsKey(rootRunId))
            throw new IllegalArgumentException("executions 缺少 root");
        for (Map<String, Object> execution : result.values()) {
            String runId = String.valueOf(execution.get("runId"));
            Set<String> visited = new HashSet<>();
            while (!rootRunId.equals(runId)) {
                if (!visited.add(runId))
                    throw new IllegalArgumentException("execution tree 存在环");
                Map<String, Object> current = result.get(runId);
                if (current == null || current.get("parentRunId") == null)
                    throw new IllegalArgumentException("execution tree 未连接 root");
                runId = String.valueOf(current.get("parentRunId"));
            }
        }
        return result;
    }

    private static String executionStatus(
            Map<?, ?> values, String key, boolean terminal) {
        String value = text(values, key, 32, true);
        if (!EXECUTION_STATUSES.contains(value) ||
                (terminal && !TERMINAL_STATUSES.contains(value)))
            throw new IllegalArgumentException(key + " 非法");
        return value;
    }

    private void requireOwnedActive(String userId, String conversationId) {
        try {
            jdbc.queryForObject("SELECT id FROM ai_conversation WHERE conversation_id=? " +
                            "AND user_id=? AND status='active' AND delete_flag=1",
                    Long.class, conversationId, userId);
        } catch (EmptyResultDataAccessException error) {
            throw new NotFoundException("会话不存在或未激活");
        }
    }

    private void requireOwned(String userId, String conversationId, boolean lock) {
        try {
            jdbc.queryForObject("SELECT id FROM ai_conversation " +
                            "WHERE conversation_id=? AND user_id=? AND delete_flag=1" +
                            (lock ? " FOR UPDATE" : ""),
                    Long.class, conversationId, userId);
        } catch (EmptyResultDataAccessException error) {
            throw new NotFoundException("会话不存在");
        }
    }

    private static String validateAguiInput(Map<String, Object> input) {
        for (String field : CLIENT_CONFIG_FIELDS) {
            if (input.containsKey(field))
                throw new IllegalArgumentException("不允许客户端控制 Agent 配置");
        }
        Object rawForwarded = input.get("forwardedProps");
        if (!(rawForwarded instanceof Map))
            throw new IllegalArgumentException("forwardedProps 非法");
        for (String field : CLIENT_CONFIG_FIELDS) {
            if (((Map<?, ?>) rawForwarded).containsKey(field))
                throw new IllegalArgumentException("不允许客户端控制 Agent 配置");
        }
        Object tools = input.get("tools");
        if (!(tools instanceof List) || !((List<?>) tools).isEmpty())
            throw new IllegalArgumentException("不支持 frontend tools");
        Object context = input.get("context");
        if (!(context instanceof List) || !((List<?>) context).isEmpty())
            throw new IllegalArgumentException("不支持 frontend context");
        Object state = input.get("state");
        if (!(state instanceof Map) || !((Map<?, ?>) state).isEmpty())
            throw new IllegalArgumentException("不支持 frontend state");
        Object resume = input.get("resume");
        if (resume != null && (!(resume instanceof List) || !((List<?>) resume).isEmpty()))
            throw new IllegalArgumentException("不支持 frontend resume");

        Object raw = input.get("messages");
        if (!(raw instanceof List) || ((List<?>) raw).size() != 1)
            throw new IllegalArgumentException("只允许提交当前用户消息");
        List<?> messages = (List<?>) raw;
        Object item = messages.get(0);
        if (!(item instanceof Map)) throw new IllegalArgumentException("用户消息非法");
        Map<?, ?> message = (Map<?, ?>) item;
        if (!"user".equals(message.get("role")) || !(message.get("id") instanceof String) ||
                !(message.get("content") instanceof String))
            throw new IllegalArgumentException("用户消息非法");
        String messageId = (String) message.get("id");
        if (messageId.isEmpty() || messageId.length() > 128)
            throw new IllegalArgumentException("用户消息 ID 非法");
        String value = ((String) message.get("content")).trim();
        if (value.isEmpty()) throw new IllegalArgumentException("缺少用户消息");
        return value.substring(0, Math.min(value.length(), 255));
    }

    private Map<String, Object> parseObject(byte[] payload, String name) {
        try {
            Object value = json.readValue(payload, new TypeReference<Object>() {});
            if (!(value instanceof Map)) throw new IllegalArgumentException(name + "必须是对象");
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) value;
            return result;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException(name + "不是有效 JSON");
        }
    }

    private static Map<String, Object> metadata(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("conversationId", rs.getString("conversationId"));
        row.put("title", rs.getString("title"));
        row.put("status", rs.getString("status"));
        row.put("executionStatus", rs.getString("executionStatus"));
        row.put("createdAt", rs.getString("createdAt"));
        row.put("updatedAt", rs.getString("updatedAt"));
        return row;
    }

    private static Identity identity(String cookieHeader) {
        return new Identity(LocalAuth.authenticate(cookieHeader));
    }

    private static void requireId(String value) {
        if (value == null || !ID.matcher(value).matches())
            throw new IllegalArgumentException("conversationId 非法");
    }

    private static void requireExecutionToken(String value) {
        if (value == null || !ID.matcher(value).matches())
            throw new IllegalArgumentException("runId 非法");
    }

    private static int integer(Map<String, Object> values, String key, int min, int max) {
        Object raw = values.get(key);
        if (!(raw instanceof Number)) throw new IllegalArgumentException(key + " 非法");
        int value = ((Number) raw).intValue();
        requireRange(value, min, max, key);
        return value;
    }

    private static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : Long.parseLong(String.valueOf(value));
    }

    private static String optionalText(Map<String, Object> values, String key, int max) {
        return text(values, key, max, false);
    }

    private static String text(Map<?, ?> values, String key, int max, boolean required) {
        Object raw = values.get(key);
        if (raw != null && !(raw instanceof String)) throw new IllegalArgumentException(key + " 非法");
        String value = raw == null ? null : ((String) raw).trim();
        if (value != null && value.isEmpty()) value = null;
        if ((required && value == null) || (value != null && value.length() > max))
            throw new IllegalArgumentException(key + " 非法");
        return value;
    }

    private static void requireRange(int value, int min, int max, String name) {
        if (value < min || value > max) throw new IllegalArgumentException(name + " 超出范围");
    }

    private static String likePrefix(String prefix) {
        return prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static Map<String, Object> mutation(Object version, boolean replayed, boolean existed) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", version == null ? null : String.valueOf(version));
        result.put("replayed", replayed);
        result.put("existed", existed);
        return result;
    }

    private static Map<String, Object> memoryError(String code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("errorCode", code);
        response.put("errorMsg", message);
        return response;
    }

    private static Map<String, Object> success() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        return response;
    }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> response = success();
        response.put("data", data);
        return response;
    }

    private static class Identity {
        private final String userId;
        private Identity(String userId) { this.userId = userId; }
    }

    private static class Operation {
        private final String id;
        private final String fingerprint;
        private Operation(String id, String fingerprint) {
            this.id = id;
            this.fingerprint = fingerprint;
        }
    }

    public static class ActiveExecutionException extends RuntimeException {
        public ActiveExecutionException() {}
    }

    public static class StaleExecutionException extends RuntimeException {}
    public static class UnauthorizedException extends RuntimeException {}
    public static class NotFoundException extends RuntimeException {
        NotFoundException(String message) { super(message); }
    }
    public static class DataCorruptionException extends IllegalStateException {}
}
