package com.union.control.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.mapper.AgentExecutionMapper;
import com.union.control.mapper.ConversationMapper;
import com.union.control.service.AgentExecutionService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static com.union.control.utils.AgentSupport.*;

@Service("agentExecutionService")
public class AgentExecutionServiceImpl implements AgentExecutionService {
    private static final String RUN_ID = "runId";
    private static final String ERROR_CODE = "errorCode";
    private static final String STATUS = "status";
    private static final String AGENT_NAME = "agentName";
    private static final String PARENT_RUN_ID = "parentRunId";
    private static final String DELEGATION_TOOL_CALL_ID = "delegationToolCallId";

    private final AgentExecutionMapper executionMapper;
    private final ConversationMapper conversationMapper;
    private final ObjectMapper json;

    public AgentExecutionServiceImpl(
            AgentExecutionMapper executionMapper,
            ConversationMapper conversationMapper,
            ObjectMapper json) {
        this.executionMapper = executionMapper;
        this.conversationMapper = conversationMapper;
        this.json = json;
    }

    @Transactional
    public Map<String, Object> claimAguiRun(String payload) {
        Map<String, Object> input = request(json, payload);
        String userId = userId(input);
        String conversationId = text(input, "threadId", 64, true);
        String runId = text(input, RUN_ID, 64, true);
        requireId(conversationId);
        requireExecutionToken(runId);
        String title = conversationTitle(input);
        try {
            conversationMapper.insertConversation(conversationId, userId, title);
        } catch (DuplicateKeyException ignored) {
            // Existing active conversation is expected on subsequent turns.
        }
        requireOwnedActive(userId, conversationId);
        executionMapper.insertRootExecution(runId, conversationId, userId);
        return ok(loadExecution(userId, conversationId, runId, false));
    }

    @Transactional
    public Map<String, Object> completeRun(String input) {
        Map<String, Object> payload = request(json, input);
        String userId = userId(payload);
        String conversationId = text(payload, "conversationId", 64, true);
        String rootRunId = text(payload, RUN_ID, 64, true);
        requireId(conversationId);
        requireExecutionToken(rootRunId);
        if (conversationMapper.requireOwned(conversationId, userId, true) == null)
            throw new NoSuchElementException("会话不存在");
        Map<String, Object> root = loadExecution(
                userId, conversationId, rootRunId, true);
        if (root.get("parentExecutionId") != null)
            throw new IllegalArgumentException("runId 不是 root execution");

        Object rawExecutions = payload.get("executions");
        if (!(rawExecutions instanceof List) || ((List<?>) rawExecutions).isEmpty())
            throw new IllegalArgumentException("executions 非法");
        Map<String, Map<String, Object>> requested = parseExecutionRows(
                (List<?>) rawExecutions,
                rootRunId);
        Object rawMessages = payload.get("messages");
        if (!(rawMessages instanceof List))
            throw new IllegalArgumentException("messages 非法");
        Map<String, Map<String, Object>> persisted = persistExecutions(
                userId, conversationId, rootRunId, root, requested);
        persistMessages(userId, conversationId, persisted, (List<?>) rawMessages);
        for (Map<String, Object> value : requested.values())
            finishExecution(persisted.get(String.valueOf(value.get(RUN_ID))),
                    String.valueOf(value.get(STATUS)), (String) value.get(ERROR_CODE));
        conversationMapper.touchConversation(conversationId, userId);
        return ok(null);
    }

    private Map<String, Map<String, Object>> persistExecutions(
            String userId, String conversationId, String rootRunId,
            Map<String, Object> root, Map<String, Map<String, Object>> requested) {
        executionMapper.updateRootAgent(number(root.get("id")),
                String.valueOf(requested.get(rootRunId).get(AGENT_NAME)));
        Map<String, Map<String, Object>> persisted = new HashMap<>();
        persisted.put(rootRunId, root);
        for (Map<String, Object> value : requested.values()) {
            String runId = String.valueOf(value.get(RUN_ID));
            if (rootRunId.equals(runId)) continue;
            String parentRunId = String.valueOf(value.get(PARENT_RUN_ID));
            Map<String, Object> parent = persisted.get(parentRunId);
            if (parent == null) throw new IllegalArgumentException("parent execution 不存在");
            executionMapper.insertChildExecution(runId, conversationId, userId,
                    number(parent.get("id")), String.valueOf(value.get(AGENT_NAME)),
                    (String) value.get(DELEGATION_TOOL_CALL_ID), (String) value.get("task"));
            persisted.put(runId, loadExecution(userId, conversationId, runId, true));
        }
        return persisted;
    }

    private void persistMessages(
            String userId, String conversationId,
            Map<String, Map<String, Object>> executions, List<?> messages) {
        Long current = conversationMapper.currentMessageSequence(conversationId);
        long sequence = current == null ? 0 : current;
        for (Object raw : messages) {
            if (!(raw instanceof Map)) throw new IllegalArgumentException("AG-UI message 必须是对象");
            Map<?, ?> envelope = (Map<?, ?>) raw;
            String messageRunId = text(envelope, RUN_ID, 64, true);
            Object rawMessage = envelope.get("message");
            if (!(rawMessage instanceof Map)) throw new IllegalArgumentException("message 非法");
            @SuppressWarnings("unchecked") Map<String, Object> message =
                    (Map<String, Object>) rawMessage;
            String messageId = text(message, "id", 128, true);
            String role = text(message, "role", 32, true);
            Map<String, Object> execution = executions.get(messageRunId);
            if (execution == null)
                throw new IllegalArgumentException("message execution 不存在");
            Map<String, Object> messagePayload = new LinkedHashMap<>(message);
            messagePayload.remove("id");
            messagePayload.remove("role");
            String messageJson;
            try {
                messageJson = json.writeValueAsString(messagePayload);
            } catch (Exception error) {
                throw new IllegalArgumentException("AG-UI message 不是有效 JSON");
            }
            conversationMapper.insertMessage(conversationId, userId, messageId,
                    number(execution.get("id")), role, ++sequence, messageJson);
        }
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

    private void finishExecution(
            Map<String, Object> execution, String status, String errorCode) {
        int updated = executionMapper.finishExecution(number(execution.get("id")), status, errorCode);
        if (updated != 1) throw new IllegalStateException("execution 已失效");
    }

    private Map<String, Map<String, Object>> parseExecutionRows(
            List<?> values, String rootRunId) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Object raw : values) {
            if (!(raw instanceof Map)) throw new IllegalArgumentException("execution 非法");
            Map<?, ?> value = (Map<?, ?>) raw;
            String runId = text(value, RUN_ID, 64, true);
            requireExecutionToken(runId);
            String parentRunId = text(value, PARENT_RUN_ID, 64, false);
            String agentName = text(value, AGENT_NAME, 128, true);
            String delegationCallId = text(value, DELEGATION_TOOL_CALL_ID, 128, false);
            String task = text(value, "task", 1000, false);
            String status = text(value, STATUS, 32, true);
            String errorCode = text(value, ERROR_CODE, 64, false);
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put(RUN_ID, runId);
            normalized.put(PARENT_RUN_ID, parentRunId);
            normalized.put(AGENT_NAME, agentName);
            normalized.put(DELEGATION_TOOL_CALL_ID, delegationCallId);
            normalized.put("task", task);
            normalized.put(STATUS, status);
            normalized.put(ERROR_CODE, errorCode);
            if (result.put(runId, normalized) != null)
                throw new IllegalArgumentException("execution runId 重复");
        }
        if (!result.containsKey(rootRunId))
            throw new IllegalArgumentException("executions 缺少 root");
        return result;
    }

    private void requireOwnedActive(String userId, String conversationId) {
        if (conversationMapper.requireOwnedActive(conversationId, userId) == null) {
            throw new NoSuchElementException("会话不存在或未激活");
        }
    }



    private static String conversationTitle(Map<String, Object> input) {
        Object raw = input.get("messages");
        if (!(raw instanceof List) || ((List<?>) raw).isEmpty()) return "新会话";
        Object item = ((List<?>) raw).get(((List<?>) raw).size() - 1);
        if (!(item instanceof Map)) return "新会话";
        Map<?, ?> message = (Map<?, ?>) item;
        if (!(message.get("content") instanceof String)) return "新会话";
        String value = ((String) message.get("content")).trim();
        if (value.isEmpty()) return "新会话";
        return value.substring(0, Math.min(value.length(), 255));
    }

}
