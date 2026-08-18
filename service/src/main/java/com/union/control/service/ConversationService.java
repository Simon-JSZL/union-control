package com.union.control.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.mapper.AgentExecutionMapper;
import com.union.control.mapper.ConversationMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static com.union.control.utils.ServiceSupport.*;

@Service
public class ConversationService {
    private final ConversationMapper conversationMapper;
    private final AgentExecutionMapper executionMapper;
    private final ObjectMapper json;

    public ConversationService(
            ConversationMapper conversationMapper,
            AgentExecutionMapper executionMapper,
            ObjectMapper json) {
        this.conversationMapper = conversationMapper;
        this.executionMapper = executionMapper;
        this.json = json;
    }

    public Map<String, Object> userInfo(String input) {
        Map<String, Object> request = request(json, input);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("userId", userId(request));
        user.put("orgCode", identity(request, "orgCode"));
        return ok(user);
    }

    public Map<String, Object> conversations(String input) {
        Map<String, Object> request = request(json, input);
        String userId = userId(request);
        int limit = integer(request, "limit", 1, 100);
        requireRange(limit, 1, 100, "limit");
        return ok(conversationMapper.findConversations(userId, limit));
    }

    public Map<String, Object> conversation(String input) {
        Map<String, Object> request = request(json, input);
        String userId = userId(request);
        String conversationId = text(request, "conversationId", 64, true);
        requireId(conversationId);
        Map<String, Object> result = loadConversation(userId, conversationId);
        result.put("messages", loadBrowserMessages(userId, conversationId));
        result.put("executions", loadExecutions(userId, conversationId));
        return ok(result);
    }

    public Map<String, Object> conversationMessages(String input) {
        Map<String, Object> payload = request(json, input);
        String userId = userId(payload);
        String conversationId = text(payload, "conversationId", 64, true);
        requireId(conversationId);
        requireOwned(userId, conversationId, false);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("messages", loadRootMessages(userId, conversationId));
        return response;
    }

    public Map<String, Object> rename(String input) {
        Map<String, Object> payload = request(json, input);
        String userId = userId(payload);
        String conversationId = text(payload, "conversationId", 64, true);
        String title = text(payload, "title", 255, true);
        requireId(conversationId);
        if (conversationMapper.updateConversationTitle(title, conversationId, userId) != 1)
            throw new NoSuchElementException("会话不存在");
        return ok(loadConversation(userId, conversationId));
    }

    @Transactional
    public Map<String, Object> deleteConversation(String input) {
        Map<String, Object> payload = request(json, input);
        String userId = userId(payload);
        String conversationId = text(payload, "conversationId", 64, true);
        requireId(conversationId);
        requireOwned(userId, conversationId, true);
        conversationMapper.softDeleteMessages(conversationId, userId);
        executionMapper.softDeleteExecutions(conversationId, userId);
        int updated = conversationMapper.softDeleteConversation(conversationId, userId);
        if (updated != 1) throw new NoSuchElementException("会话不存在");
        return ok(null);
    }

    /** Materializes a trusted completed result through the normal conversation path. */
    @Transactional
    String materializeCompletedConversation(
            String userId, String title, String prompt, String content, String agentName) {
        String random = UUID.randomUUID().toString().replace("-", "");
        String conversationId = "scheduled-" + random;
        String runId = "scheduled-open-" + random;
        conversationMapper.insertConversation(conversationId, userId, title);
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("runId", runId);
        execution.put("conversationId", conversationId);
        execution.put("userId", userId);
        execution.put("agentName", agentName);
        executionMapper.insertCompletedRootExecution(execution);
        insertTrustedMessage(conversationId, userId,
                "scheduled-user-" + random, number(execution.get("id")), "user", 1, prompt);
        insertTrustedMessage(conversationId, userId,
                "scheduled-assistant-" + random, number(execution.get("id")), "assistant", 2, content);
        return conversationId;
    }

    private void insertTrustedMessage(
            String conversationId, String userId, String messageId, long executionId,
            String role, long sequence, String content) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", content);
        try {
            conversationMapper.insertMessage(conversationId, userId, messageId, executionId,
                    role, sequence, json.writeValueAsString(payload));
        } catch (Exception error) {
            throw new IllegalArgumentException("可信消息无法持久化", error);
        }
    }


    private List<Object> loadRootMessages(String userId, String conversationId) {
        List<Map<String, Object>> rows = conversationMapper.findRootMessages(userId, conversationId);
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
        List<Map<String, Object>> rows = conversationMapper.findBrowserMessages(userId, conversationId);
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
            if (!(parsed instanceof Map)) throw new IllegalStateException("消息数据损坏");
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("id", row.get("messageId"));
            message.put("role", row.get("role"));
            @SuppressWarnings("unchecked") Map<String, Object> payload =
                    (Map<String, Object>) parsed;
            message.putAll(payload);
            return message;
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("消息数据损坏", error);
        }
    }

    private static Map<String, Object> activity(
            Map<String, Object> execution, List<Object> messages) {
        if (execution == null) throw new IllegalStateException("execution 数据损坏");
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
            Map<String, Object> conversation =
                    conversationMapper.findConversation(userId, conversationId);
            if (conversation == null) throw new NoSuchElementException("会话不存在");
            return conversation;
        } catch (EmptyResultDataAccessException error) {
            throw new NoSuchElementException("会话不存在");
        }
    }

    private List<Map<String, Object>> loadExecutionRows(String userId, String conversationId) {
        return executionMapper.findExecutions(userId, conversationId);
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


    private void requireOwned(String userId, String conversationId, boolean lock) {
        try {
            conversationMapper.requireOwned(conversationId, userId, lock);
        } catch (EmptyResultDataAccessException error) {
            throw new NoSuchElementException("会话不存在");
        }
    }
}
