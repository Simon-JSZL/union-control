package com.union.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.mapper.AgentExecutionMapper;
import com.union.control.mapper.ConversationMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import static com.union.control.utils.ServiceSupport.*;

@Service
public class AgentExecutionService {
    private static final List<String> CLIENT_CONFIG_FIELDS = Arrays.asList(
            "agent", "agentName", "skill", "skillId", "memoryNamespace",
            "model", "provider");
    private static final Set<String> EXECUTION_STATUSES = new HashSet<>(Arrays.asList(
            "running", "cancel_requested", "completed", "failed", "cancelled"));
    private static final Set<String> TERMINAL_STATUSES = new HashSet<>(Arrays.asList(
            "completed", "failed", "cancelled"));
    private static final Set<String> MESSAGE_ROLES = new HashSet<>(Arrays.asList(
            "user", "assistant", "tool", "system", "developer", "reasoning"));

    private final AgentExecutionMapper executionMapper;
    private final ConversationMapper conversationMapper;
    private final ObjectMapper json;

    @Value("${agent.execution-max-age-seconds:900}")
    private int executionMaxAgeSeconds;

    @Value("${agent.cancel-request-max-age-seconds:30}")
    private int cancelRequestMaxAgeSeconds;

    public AgentExecutionService(
            AgentExecutionMapper executionMapper,
            ConversationMapper conversationMapper,
            ObjectMapper json) {
        this.executionMapper = executionMapper;
        this.conversationMapper = conversationMapper;
        this.json = json;
    }

    @EventListener(ContextRefreshedEvent.class)
    @Scheduled(fixedDelay = 30000)
    @Transactional
    public void cleanupStaleExecutions() {
        executionMapper.cleanupStaleExecutionChildren(
                executionMaxAgeSeconds, cancelRequestMaxAgeSeconds);
        executionMapper.cleanupStaleExecutionRoots(
                executionMaxAgeSeconds, cancelRequestMaxAgeSeconds);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> claimAguiRun(String payload) {
        Map<String, Object> input = request(json, payload);
        String userId = userId(input);
        String conversationId = text(input, "threadId", 64, true);
        String runId = text(input, "runId", 64, true);
        requireId(conversationId);
        requireExecutionToken(runId);
        String title = validateAguiInput(input);
        cleanupStaleExecutions();
        Map<String, Object> active = loadCurrentRoot(userId, false);
        if (active != null) throw new IllegalStateException("存在进行中的 execution");
        try {
            conversationMapper.insertConversation(conversationId, userId, title);
        } catch (DuplicateKeyException ignored) {
            // Existing active conversation is expected on subsequent turns.
        }
        requireOwnedActive(userId, conversationId);
        try {
            executionMapper.insertRootExecution(runId, conversationId, userId);
        } catch (DuplicateKeyException error) {
            active = loadCurrentRoot(userId, false);
            if (active != null) throw new IllegalStateException("存在进行中的 execution");
            throw error;
        }
        return ok(loadExecution(userId, conversationId, runId, false));
    }

    @Transactional
    public Map<String, Object> cancelExecution(String input) {
        Map<String, Object> payload = request(json, input);
        String userId = userId(payload);
        String conversationId = text(payload, "conversationId", 64, true);
        String runId = text(payload, "runId", 64, true);
        String reason = optionalText(payload, "reason", 64);
        if (reason == null) reason = "cancelled";
        Map<String, Object> root = loadExecution(
                userId, conversationId, runId, true);
        if (root.get("parentExecutionId") != null)
            throw new IllegalArgumentException("runId 不是 root execution");
        requestCancellation(root, reason);
        return ok(publicExecution(loadExecution(
                userId, conversationId, runId, false)));
    }

    @Transactional
    public void failExecution(String input) {
        Map<String, Object> payload = request(json, input);
        String userId = userId(payload);
        String conversationId = text(payload, "conversationId", 64, true);
        String runId = text(payload, "runId", 64, true);
        String errorCode = text(payload, "errorCode", 64, true);
        Map<String, Object> root = loadExecution(
                userId, conversationId, runId, true);
        if (root.get("parentExecutionId") != null)
            throw new IllegalArgumentException("runId 不是 root execution");
        String status = "cancel_requested".equals(root.get("status")) ||
                "execution_timeout".equals(errorCode)
                ? "cancelled" : "failed";
        String terminalError = status.equals("cancelled")
                ? (root.get("errorCode") == null ? "cancelled" : String.valueOf(root.get("errorCode")))
                : errorCode;
        executionMapper.updateChildrenStatus(number(root.get("id")), status, terminalError);
        finishExecution(root, status, errorCode);
    }

    @Transactional
    public Map<String, Object> rootExecutionSelected(String input) {
        Map<String, Object> payload = request(json, input);
        String userId = userId(payload);
        String conversationId = text(payload, "conversationId", 64, true);
        String runId = text(payload, "runId", 64, true);
        String agentName = text(payload, "agentName", 128, true);
        Map<String, Object> root = loadExecution(
                userId, conversationId, runId, true);
        if (root.get("parentExecutionId") != null)
            throw new IllegalArgumentException("runId 不是 root execution");
        String current = String.valueOf(root.get("agentName"));
        if (current.equals(agentName)) return ok(publicExecution(root));
        if (!"UnionCoordinatorAgent".equals(current) ||
                !"running".equals(root.get("status")))
            throw new IllegalStateException("execution 已失效");
        int updated = executionMapper.updateRootAgent(number(root.get("id")), agentName, current);
        if (updated != 1) throw new IllegalStateException("execution 已失效");
        return ok(publicExecution(loadExecution(
                userId, conversationId, runId, false)));
    }

    @Transactional
    public Map<String, Object> executionStarted(String input) {
        Map<String, Object> payload = request(json, input);
        String userId = userId(payload);
        String conversationId = text(payload, "conversationId", 64, true);
        String runId = text(payload, "runId", 64, true);
        String parentRunId = text(payload, "parentRunId", 64, true);
        String agentName = text(payload, "agentName", 128, true);
        String delegationCallId = text(payload, "delegationToolCallId", 128, true);
        String task = text(payload, "task", 1000, true);
        requireId(conversationId);
        requireExecutionToken(runId);
        requireExecutionToken(parentRunId);
        requireOwnedActive(userId, conversationId);
        Map<String, Object> parent = loadExecution(
                userId, conversationId, parentRunId, true);
        String parentStatus = String.valueOf(parent.get("status"));
        if (!"running".equals(parentStatus))
            throw new IllegalStateException("execution 已失效");
        try {
            executionMapper.insertChildExecution(runId, conversationId, userId,
                    number(parent.get("id")), agentName, delegationCallId, task);
        } catch (DuplicateKeyException error) {
            Map<String, Object> existing = loadExecution(
                    userId, conversationId, runId, false);
            if (!sameExecution(existing, parentRunId, agentName, delegationCallId, task))
                throw new IllegalArgumentException("child execution 幂等冲突");
        }
        return ok(loadExecution(userId, conversationId, runId, false));
    }

    @Transactional
    public Map<String, Object> executionFinished(String input) {
        Map<String, Object> payload = request(json, input);
        String userId = userId(payload);
        String conversationId = text(payload, "conversationId", 64, true);
        String runId = text(payload, "runId", 64, true);
        String status = executionStatus(payload, "status", true);
        String errorCode = optionalText(payload, "errorCode", 64);
        Map<String, Object> execution = loadExecution(
                userId, conversationId, runId, true);
        if (execution.get("parentExecutionId") == null)
            throw new IllegalArgumentException("root execution 只能由 completeRun 完成");
        finishExecution(execution, status, errorCode);
        return ok(null);
    }

    @Transactional
    public Map<String, Object> completeRun(String input) {
        Map<String, Object> payload = request(json, input);
        String userId = userId(payload);
        String conversationId = text(payload, "conversationId", 64, true);
        String rootRunId = text(payload, "runId", 64, true);
        String rootStatus = executionStatus(payload, "status", true);
        String rootErrorCode = optionalText(payload, "errorCode", 64);
        requireId(conversationId);
        requireExecutionToken(rootRunId);
        Map<String, Object> root = loadExecution(
                userId, conversationId, rootRunId, true);
        if (root.get("parentExecutionId") != null)
            throw new IllegalArgumentException("runId 不是 root execution");
        String currentRootStatus = String.valueOf(root.get("status"));
        boolean replayOnly = TERMINAL_STATUSES.contains(currentRootStatus);
        boolean cancellationWins = "cancel_requested".equals(currentRootStatus);
        if (replayOnly && (!currentRootStatus.equals(rootStatus) ||
                !same((String) root.get("errorCode"), rootErrorCode)))
            return ok(null);
        if (cancellationWins && !"cancelled".equals(rootStatus)) {
            String cancellationError = root.get("errorCode") == null
                    ? "cancelled" : String.valueOf(root.get("errorCode"));
            executionMapper.updateChildrenStatus(number(root.get("id")), "cancelled", cancellationError);
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
                        userId, conversationId, runId, true);
                if (existing == null) {
                    if (replayOnly)
                        throw new IllegalArgumentException("execution 终态重放冲突");
                    executionMapper.insertChildExecution(runId, conversationId, userId, parentId,
                            String.valueOf(value.get("agentName")),
                            (String) value.get("delegationToolCallId"), (String) value.get("task"));
                    existing = loadExecution(userId, conversationId, runId, true);
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
        Long current = conversationMapper.currentMessageSequence(conversationId);
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
                conversationMapper.insertMessage(conversationId, userId, messageId, executionId, role,
                        ++sequence, json.writeValueAsString(messagePayload));
            } catch (DuplicateKeyException ignored) {
                --sequence;
                requireSameMessage(
                        conversationId, messageId, executionId, role, messagePayload);
            } catch (Exception error) {
                throw new IllegalArgumentException("AG-UI message 不是有效 JSON");
            }
        }
        for (Map<String, Object> value : requested.values()) {
            Map<String, Object> execution = loadExecution(
                    userId, conversationId, String.valueOf(value.get("runId")), true);
            finishExecution(execution, String.valueOf(value.get("status")),
                    (String) value.get("errorCode"));
        }
        if (!cancellationWins &&
                (!rootStatus.equals(requested.get(rootRunId).get("status")) ||
                !same(rootErrorCode, (String) requested.get(rootRunId).get("errorCode"))))
            throw new IllegalArgumentException("root execution 状态不一致");
        conversationMapper.touchConversation(conversationId, userId);
        return ok(null);
    }


    private static Map<String, Object> publicExecution(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.remove("id");
        result.remove("parentExecutionId");
        return result;
    }

    private Map<String, Object> findExecution(
            String userId, String conversationId, String runId, boolean lock) {
        List<Map<String, Object>> rows = executionMapper.findExecution(
                userId, conversationId, runId, lock);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> loadExecution(
            String userId, String conversationId, String runId, boolean lock) {
        requireId(conversationId);
        requireExecutionToken(runId);
        Map<String, Object> execution = findExecution(userId, conversationId, runId, lock);
        if (execution == null) throw new IllegalStateException("execution 已失效");
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
            throw new IllegalStateException("execution 已失效");
        executionMapper.requestChildrenCancellation(reason, number(root.get("id")));
        executionMapper.requestRootCancellation(reason, number(root.get("id")));
    }

    private void finishExecution(
            Map<String, Object> execution, String status, String errorCode) {
        if (!TERMINAL_STATUSES.contains(status))
            throw new IllegalArgumentException("execution 终态非法");
        String current = String.valueOf(execution.get("status"));
        if (TERMINAL_STATUSES.contains(current)) return;
        if (!"running".equals(current) && !"cancel_requested".equals(current))
            throw new IllegalStateException("execution 已失效");
        if ("cancel_requested".equals(current)) {
            status = "cancelled";
            errorCode = (String) execution.get("errorCode");
            if (errorCode == null) errorCode = "cancelled";
        }
        int updated = executionMapper.finishExecution(number(execution.get("id")), status, errorCode);
        if (updated != 1) throw new IllegalStateException("execution 已失效");
    }

    private void requireSameMessage(
            String conversationId, String messageId, Long executionId,
            String role, Map<String, Object> messagePayload) {
        try {
            Map<String, Object> existing = conversationMapper.findMessage(conversationId, messageId);
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
            conversationMapper.requireOwnedActive(conversationId, userId);
        } catch (EmptyResultDataAccessException error) {
            throw new NoSuchElementException("会话不存在或未激活");
        }
    }



    private Map<String, Object> loadCurrentRoot(String userId, boolean lock) {
        List<Map<String, Object>> rows = executionMapper.findCurrentRoots(userId, lock);
        return rows.isEmpty() ? null : rows.get(0);
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

}
