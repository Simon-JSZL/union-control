package com.union.control.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.mapper.AgentExecutionMapper;
import com.union.control.mapper.ConversationMapper;
import com.union.control.mapper.ScheduledTaskMapper;
import com.union.control.service.ConversationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static com.union.control.utils.AgentSupport.*;

@Service("conversationService")
public class ConversationServiceImpl implements ConversationService {
    private static final String CONVERSATION_ID = "conversationId";
    private static final String MESSAGES = "messages";
    private static final String CONVERSATION_NOT_FOUND = "会话不存在";
    private static final String RUN_ID = "runId";
    private static final String AGENT_NAME = "agentName";
    private static final String PARENT_EXECUTION_ID = "parentExecutionId";

    private final ConversationMapper conversationMapper;
    private final AgentExecutionMapper executionMapper;
    private final ScheduledTaskMapper scheduledTaskMapper;
    private final ObjectMapper json;

    public ConversationServiceImpl(
            ConversationMapper conversationMapper,
            AgentExecutionMapper executionMapper,
            ScheduledTaskMapper scheduledTaskMapper,
            ObjectMapper json) {
        this.conversationMapper = conversationMapper;
        this.executionMapper = executionMapper;
        this.scheduledTaskMapper = scheduledTaskMapper;
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
        String conversationId = text(request, CONVERSATION_ID, 64, true);
        requireId(conversationId);
        Map<String, Object> result = loadConversation(userId, conversationId);
        List<Map<String, Object>> executions = executionMapper.findExecutions(userId, conversationId);
        result.put(MESSAGES, loadBrowserMessages(userId, conversationId, executions));
        result.put("executions", publicExecutions(executions));
        return ok(result);
    }

    public Map<String, Object> conversationMessages(String input) {
        Map<String, Object> payload = request(json, input);
        String userId = userId(payload);
        String conversationId = text(payload, CONVERSATION_ID, 64, true);
        requireId(conversationId);
        requireOwned(userId, conversationId, false);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put(MESSAGES, loadRootMessages(userId, conversationId));
        return response;
    }

    public Map<String, Object> rename(String input) {
        Map<String, Object> payload = request(json, input);
        String userId = userId(payload);
        String conversationId = text(payload, CONVERSATION_ID, 64, true);
        String title = text(payload, "title", 255, true);
        requireId(conversationId);
        if (conversationMapper.updateConversationTitle(title, conversationId, userId) != 1)
            throw new NoSuchElementException(CONVERSATION_NOT_FOUND);
        return ok(loadConversation(userId, conversationId));
    }

    @Transactional
    public Map<String, Object> deleteConversation(String input) {
        Map<String, Object> payload = request(json, input);
        String userId = userId(payload);
        String conversationId = text(payload, CONVERSATION_ID, 64, true);
        requireId(conversationId);
        requireOwned(userId, conversationId, true);
        conversationMapper.softDeleteMessages(conversationId, userId);
        executionMapper.softDeleteExecutions(conversationId, userId);
        scheduledTaskMapper.softDeleteByConversation(conversationId, userId);
        int updated = conversationMapper.softDeleteConversation(conversationId, userId);
        if (updated != 1) throw new NoSuchElementException(CONVERSATION_NOT_FOUND);
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
        execution.put(RUN_ID, runId);
        execution.put(CONVERSATION_ID, conversationId);
        execution.put("userId", userId);
        execution.put(AGENT_NAME, agentName);
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

    private List<Object> loadBrowserMessages(
            String userId, String conversationId, List<Map<String, Object>> executions) {
        Map<String, Map<String, Object>> byRun = new LinkedHashMap<>();
        for (Map<String, Object> execution : executions)
            byRun.put(String.valueOf(execution.get(RUN_ID)), execution);
        List<Map<String, Object>> rows = conversationMapper.findBrowserMessages(userId, conversationId);
        List<Object> result = new ArrayList<>();
        Map<String, List<Object>> childMessages = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            if (row.get(PARENT_EXECUTION_ID) == null) {
                result.add(message(row));
                continue;
            }
            String runId = String.valueOf(row.get(RUN_ID));
            List<Object> messages = childMessages.get(runId);
            if (messages == null) {
                messages = new ArrayList<>();
                childMessages.put(runId, messages);
                result.add(activity(byRun.get(runId), messages));
            }
            messages.add(message(row));
        }
        for (Map<String, Object> execution : executions) {
            if (execution.get(PARENT_EXECUTION_ID) == null) continue;
            String runId = String.valueOf(execution.get(RUN_ID));
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
        content.put(RUN_ID, execution.get(RUN_ID));
        content.put("parentRunId", execution.get("parentRunId"));
        content.put(AGENT_NAME, execution.get(AGENT_NAME));
        content.put("delegationToolCallId", execution.get("delegationToolCallId"));
        content.put("task", execution.get("task"));
        content.put("status", execution.get("status"));
        content.put(MESSAGES, messages);
        content.put("errorCode", execution.get("errorCode"));
        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("id", "subagent:" + execution.get(RUN_ID));
        activity.put("role", "activity");
        activity.put("activityType", "subagent_execution");
        activity.put("content", content);
        return activity;
    }

    private Map<String, Object> loadConversation(String userId, String conversationId) {
        Map<String, Object> conversation =
                conversationMapper.findConversation(userId, conversationId);
        if (conversation == null) throw new NoSuchElementException(CONVERSATION_NOT_FOUND);
        return conversation;
    }

    private static List<Map<String, Object>> publicExecutions(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) result.add(publicExecution(row));
        return result;
    }

    private static Map<String, Object> publicExecution(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.remove("id");
        result.remove(PARENT_EXECUTION_ID);
        return result;
    }


    private void requireOwned(String userId, String conversationId, boolean lock) {
        if (conversationMapper.requireOwned(conversationId, userId, lock) == null) {
            throw new NoSuchElementException(CONVERSATION_NOT_FOUND);
        }
    }
}
