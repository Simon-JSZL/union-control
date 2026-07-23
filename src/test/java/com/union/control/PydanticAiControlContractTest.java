package com.union.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.service.ControlService;
import com.union.control.service.LocalAuth;
import org.junit.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.anyVararg;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

public class PydanticAiControlContractTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ControlService service = new ControlService(jdbc, new ObjectMapper());

    @Test
    public void springSelectsTheCookieAuthenticatedAgentControllerConstructor() {
        java.lang.reflect.Constructor<?>[] constructors = new AutowiredAnnotationBeanPostProcessor()
                .determineCandidateConstructors(
                        com.union.control.controller.AgentController.class,
                        "agentController");
        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterTypes()).containsExactly(ControlService.class);
    }

    @Test
    public void authenticatedIdentityComesFromTheCookie() {
        Map<String, Object> response = service.userInfo(LocalAuth.cookieHeader());
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) response.get("data");
        assertThat(user.get("userId")).isEqualTo(LocalAuth.USER_ID);
        assertThat(user).doesNotContainKey("memoryNamespace");
    }

    @Test
    public void conversationListReturnsLatestRootExecutionStatus() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("conversationId")).thenReturn("thread-1");
        when(row.getString("title")).thenReturn("question");
        when(row.getString("status")).thenReturn("active");
        when(row.getString("executionStatus")).thenReturn("running");
        when(jdbc.query(
                org.mockito.Matchers.contains("parent_execution_id IS NULL"),
                any(RowMapper.class), eq(LocalAuth.USER_ID), eq(100)))
                .thenAnswer(invocation -> Collections.singletonList(
                        ((RowMapper<?>) invocation.getArguments()[1]).mapRow(row, 0)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conversations = (List<Map<String, Object>>) service
                .conversations(LocalAuth.cookieHeader(), 100).get("data");

        assertThat(conversations).hasSize(1);
        assertThat(conversations.get(0).get("executionStatus")).isEqualTo("running");
        verify(jdbc).query(org.mockito.Matchers.contains(
                "ORDER BY c.created_at DESC,c.id DESC"),
                any(RowMapper.class), eq(LocalAuth.USER_ID), eq(100));
    }

    @Test
    public void staleExecutionCleanupTerminatesChildrenBeforeRoots() {
        service.cleanupStaleExecutions();

        verify(jdbc).update(org.mockito.Matchers.contains(
                "JOIN ai_agent_execution r"), (Object[]) anyVararg());
        verify(jdbc).update(org.mockito.Matchers.contains(
                "WHERE parent_execution_id IS NULL"), (Object[]) anyVararg());
    }

    @Test
    public void cancellationCascadesToTheOwnedRootAndItsChildren() {
        Map<String, Object> root = execution(
                1L, "root-1", null, null, "UnionCoordinatorAgent", null, null, "running");
        when(jdbc.queryForList(org.mockito.Matchers.contains("e.run_id=?"),
                (Object[]) anyVararg())).thenReturn(Collections.singletonList(root));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", "thread-1");
        payload.put("runId", "root-1");

        service.cancelExecution(LocalAuth.cookieHeader(), payload);

        verify(jdbc).update(org.mockito.Matchers.contains(
                "WHERE parent_execution_id=? AND status='running'"),
                (Object[]) anyVararg());
        verify(jdbc).update(org.mockito.Matchers.contains(
                "WHERE id=? AND status='running'"),
                (Object[]) anyVararg());
    }

    @Test
    public void committedCancellationCannotBecomeCompleted() {
        Map<String, Object> child = execution(
                2L, "child-1", 1L, "root-1", "KnowledgeAgent",
                "delegate-1", "task", "cancel_requested");
        child.put("errorCode", "client_disconnected");
        when(jdbc.queryForList(org.mockito.Matchers.contains("e.run_id=?"),
                (Object[]) anyVararg())).thenReturn(Collections.singletonList(child));
        when(jdbc.update(org.mockito.Matchers.contains(
                "WHERE id=? AND delete_flag=1"),
                (Object[]) anyVararg())).thenReturn(1);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", "thread-1");
        payload.put("runId", "child-1");
        payload.put("status", "completed");
        payload.put("errorCode", null);

        service.executionFinished(LocalAuth.cookieHeader(), payload);

        verify(jdbc).update(org.mockito.Matchers.contains(
                        "UPDATE ai_agent_execution SET status=?"),
                org.mockito.Matchers.eq("cancelled"),
                org.mockito.Matchers.eq("client_disconnected"),
                org.mockito.Matchers.eq(2L));
    }

    @Test
    public void committedCancellationRejectsLateChildStart() {
        Map<String, Object> root = execution(
                1L, "root-1", null, null, "UnionCoordinatorAgent",
                null, null, "cancel_requested");
        when(jdbc.queryForObject(anyString(), org.mockito.Matchers.eq(Long.class),
                (Object[]) anyVararg())).thenReturn(1L);
        when(jdbc.queryForList(org.mockito.Matchers.contains("e.run_id=?"),
                (Object[]) anyVararg())).thenReturn(Collections.singletonList(root));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", "thread-1");
        payload.put("runId", "child-1");
        payload.put("parentRunId", "root-1");
        payload.put("agentName", "KnowledgeAgent");
        payload.put("delegationToolCallId", "delegate-1");
        payload.put("task", "task");

        assertThatThrownBy(() -> service.executionStarted(
                LocalAuth.cookieHeader(), payload))
                .isInstanceOf(ControlService.StaleExecutionException.class);
    }

    @Test
    public void rootSelectionIsOwnedAndIdempotent() {
        Map<String, Object> root = execution(
                1L, "root-1", null, null, "UnionCoordinatorAgent", null, null, "running");
        when(jdbc.queryForList(org.mockito.Matchers.contains("e.run_id=?"),
                (Object[]) anyVararg())).thenReturn(Collections.singletonList(root));
        when(jdbc.update(org.mockito.Matchers.contains(
                "UPDATE ai_agent_execution SET agent_name=?"),
                (Object[]) anyVararg())).thenReturn(1);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", "thread-1");
        payload.put("runId", "root-1");
        payload.put("agentName", "KnowledgeAgent");

        service.rootExecutionSelected(LocalAuth.cookieHeader(), payload);

        verify(jdbc).update(org.mockito.Matchers.contains(
                "UPDATE ai_agent_execution SET agent_name=?"),
                (Object[]) anyVararg());
    }

    @Test
    public void directHandoffRootCanCompleteWithTheSelectedAgent() {
        Map<String, Object> root = execution(
                1L, "root-1", null, null, "KnowledgeAgent", null, null, "running");
        when(jdbc.queryForList(org.mockito.Matchers.contains("e.run_id=?"),
                (Object[]) anyVararg())).thenReturn(Collections.singletonList(root));
        when(jdbc.queryForObject(org.mockito.Matchers.contains("MAX(sequence_no)"),
                org.mockito.Matchers.eq(Long.class), (Object[]) anyVararg())).thenReturn(0L);
        when(jdbc.update(org.mockito.Matchers.contains(
                "UPDATE ai_agent_execution SET status=?"),
                (Object[]) anyVararg())).thenReturn(1);
        Map<String, Object> payload = completion("answer");
        @SuppressWarnings("unchecked")
        Map<String, Object> execution = (Map<String, Object>)
                ((List<?>) payload.get("executions")).get(0);
        execution.put("agentName", "KnowledgeAgent");

        assertThat(service.completeRun(LocalAuth.cookieHeader(), payload).get("success"))
                .isEqualTo(true);
    }

    @Test
    public void losingCompletionCannotAppendToACancelledRun() {
        Map<String, Object> root = execution(
                1L, "root-1", null, null, "KnowledgeAgent", null, null, "cancelled");
        root.put("errorCode", "client_disconnected");
        when(jdbc.queryForList(org.mockito.Matchers.contains("e.run_id=?"),
                (Object[]) anyVararg())).thenReturn(Collections.singletonList(root));
        Map<String, Object> payload = completion("late answer");
        @SuppressWarnings("unchecked")
        Map<String, Object> execution = (Map<String, Object>)
                ((List<?>) payload.get("executions")).get(0);
        execution.put("agentName", "KnowledgeAgent");

        assertThat(service.completeRun(LocalAuth.cookieHeader(), payload).get("success"))
                .isEqualTo(true);

        verify(jdbc, never()).queryForObject(org.mockito.Matchers.contains("MAX(sequence_no)"),
                org.mockito.Matchers.eq(Long.class), (Object[]) anyVararg());
    }

    @Test
    public void aguiRunRejectsFrontendToolsBeforePersistence() {
        String body = "{\"threadId\":\"thread-1\",\"runId\":\"run-1\"," +
                "\"messages\":[{\"id\":\"m1\",\"role\":\"user\",\"content\":\"hello\"}]," +
                "\"state\":{},\"context\":[],\"forwardedProps\":{}," +
                "\"tools\":[{\"name\":\"unsafe\"}]}";
        assertThatThrownBy(() -> service.claimAguiRun(
                LocalAuth.cookieHeader(), body.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frontend tools");
    }

    @Test
    public void memoryPathCannotEscapeAuthenticatedUserNamespace() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("path", "another-user/personal/profile.md");
        payload.put("maxChars", 100);
        assertThatThrownBy(() -> service.memoryRead(LocalAuth.cookieHeader(), payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memory path");
    }

    @Test
    public void schemaIsTheFiveTableExecutionModel() throws Exception {
        String schema = new String(
                Files.readAllBytes(Paths.get("src/main/resources/schema.sql")),
                StandardCharsets.UTF_8);
        assertThat(count(schema, "CREATE TABLE")).isEqualTo(5);
        assertThat(schema).contains(
                "CREATE TABLE IF NOT EXISTS `ai_conversation`",
                "CREATE TABLE IF NOT EXISTS `ai_agent_execution`",
                "CREATE TABLE IF NOT EXISTS `ai_conversation_message`",
                "`agent_execution_id` BIGINT NOT NULL",
                "`sequence_no` BIGINT NOT NULL",
                "`payload` JSON NOT NULL",
                "`last_operation_id`",
                "UNIQUE KEY `uk_memory_operation` (`user_id`, `operation_id`)",
                "UNIQUE KEY `uk_active_root_user`");
        assertEveryColumnHasComment(schema);
        assertThat(schema).contains(
                "COMMENT '会话状态：active-活跃，archived-已归档'",
                "COMMENT '执行状态：running-执行中，cancel_requested-已请求取消，completed-已完成，failed-执行失败，cancelled-已取消'",
                "COMMENT '消息角色：user-用户，assistant-助手，tool-工具结果，system-系统，developer-开发者，reasoning-推理'",
                "COMMENT '删除标记：1-有效，0-已删除'",
                "COMMENT '操作前文件是否存在：1-存在，0-不存在'");
        for (String legacy : Arrays.asList(
                "expires_at", "execution_status", "execution_token",
                "execution_started_at", "message_json", "active_message_id",
                "active_seq", "active_operation_id")) {
            assertThat(schema).doesNotContain(legacy);
        }
        for (String table : Arrays.asList(
                "ai_conversation", "ai_agent_execution", "ai_conversation_message",
                "ai_memory_file", "ai_memory_operation")) {
            String definition = schema.substring(
                    schema.indexOf("CREATE TABLE IF NOT EXISTS `" + table + "`"));
            definition = definition.substring(0, definition.indexOf("ENGINE=InnoDB"));
            assertThat(definition).contains(
                    "`id` BIGINT NOT NULL AUTO_INCREMENT",
                    "`delete_flag`",
                    "`created_at`",
                    "`updated_at`");
        }
    }

    private static void assertEveryColumnHasComment(String schema) {
        StringBuilder columnDefinition = null;
        for (String line : schema.split("\\r?\\n")) {
            if (line.startsWith("  `")) {
                columnDefinition = new StringBuilder(line);
            } else if (columnDefinition != null) {
                columnDefinition.append('\n').append(line);
            }
            if (columnDefinition != null && line.trim().endsWith(",")) {
                assertThat(columnDefinition.toString())
                        .as("数据库列定义必须包含中文 COMMENT：%s", columnDefinition)
                        .contains(" COMMENT '");
                columnDefinition = null;
            }
        }
    }

    @Test
    public void memoryCasConflictIsReturnedWithoutMutation() {
        when(jdbc.queryForList(
                org.mockito.Matchers.contains("ai_memory_operation"),
                (Object[]) anyVararg())).thenReturn(Collections.<Map<String, Object>>emptyList());
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("id", 1L);
        current.put("version", 2L);
        when(jdbc.queryForList(
                org.mockito.Matchers.contains("ai_memory_file"),
                (Object[]) anyVararg())).thenReturn(Collections.singletonList(current));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("path", LocalAuth.USER_ID + "/personal/MEMORY.md");
        payload.put("content", "new value");
        payload.put("expectedVersion", "1");

        Map<String, Object> response = service.memoryWrite(LocalAuth.cookieHeader(), payload);
        assertThat(response.get("success")).isEqualTo(false);
        assertThat(response.get("errorCode")).isEqualTo("version_conflict");
    }

    @Test
    public void browserHistoryReconstructsChildAsActivityMessage() {
        when(jdbc.queryForObject(anyString(), org.mockito.Matchers.eq(Long.class),
                (Object[]) anyVararg())).thenReturn(1L);
        Map<String, Object> conversation = new LinkedHashMap<>();
        conversation.put("conversationId", "thread-1");
        conversation.put("title", "test");
        conversation.put("status", "active");
        when(jdbc.queryForMap(org.mockito.Matchers.contains("FROM ai_conversation"),
                (Object[]) anyVararg())).thenReturn(conversation);

        Map<String, Object> root = execution(
                1L, "root-1", null, null, "UnionCoordinatorAgent", null, null, "completed");
        Map<String, Object> child = execution(
                2L, "child-1", 1L, "root-1", "RunningAnalysisAgent",
                "delegate-1", "计算指标", "completed");
        when(jdbc.queryForList(org.mockito.Matchers.contains("FROM ai_agent_execution e"),
                (Object[]) anyVararg())).thenReturn(Arrays.asList(root, child));

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(messageRow("user-1", "user", "{\"content\":\"问题\"}", "root-1", null));
        rows.add(messageRow("child-answer", "assistant",
                "{\"content\":\"指标计算完成\"}", "child-1", 1L));
        rows.add(messageRow("root-answer", "assistant",
                "{\"content\":\"综合回答\"}", "root-1", null));
        when(jdbc.queryForList(org.mockito.Matchers.contains(
                "SELECT m.message_id AS messageId"),
                (Object[]) anyVararg())).thenReturn(rows);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) service.conversation(
                LocalAuth.cookieHeader(), "thread-1").get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) data.get("messages");

        assertThat(messages).hasSize(3);
        assertThat(messages.get(1).get("role")).isEqualTo("activity");
        @SuppressWarnings("unchecked")
        Map<String, Object> activity = (Map<String, Object>) messages.get(1).get("content");
        assertThat(activity.get("runId")).isEqualTo("child-1");
        assertThat(activity.get("agentName")).isEqualTo("RunningAnalysisAgent");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> childMessages =
                (List<Map<String, Object>>) activity.get("messages");
        assertThat(childMessages.get(0).get("content")).isEqualTo("指标计算完成");
        assertThat(messages.get(2).get("content")).isEqualTo("综合回答");
    }

    @Test
    public void modelHistoryQuerySelectsRootExecutionsOnly() {
        when(jdbc.queryForObject(anyString(), org.mockito.Matchers.eq(Long.class),
                (Object[]) anyVararg())).thenReturn(1L);
        when(jdbc.queryForList(org.mockito.Matchers.contains(
                "e.parent_execution_id IS NULL"),
                (Object[]) anyVararg())).thenReturn(Collections.<Map<String, Object>>emptyList());

        service.conversationMessages(
                LocalAuth.cookieHeader(),
                Collections.<String, Object>singletonMap("conversationId", "thread-1"));

        verify(jdbc).queryForList(org.mockito.Matchers.contains(
                "e.parent_execution_id IS NULL"),
                (Object[]) anyVararg());
    }

    @Test
    public void completionReplayRequiresTheSameExecutionRoleAndPayload() {
        Map<String, Object> root = execution(
                1L, "root-1", null, null, "UnionCoordinatorAgent", null, null, "completed");
        when(jdbc.queryForList(org.mockito.Matchers.contains("e.run_id=?"),
                (Object[]) anyVararg())).thenReturn(Collections.singletonList(root));
        when(jdbc.queryForObject(org.mockito.Matchers.contains("MAX(sequence_no)"),
                org.mockito.Matchers.eq(Long.class), (Object[]) anyVararg())).thenReturn(1L);
        when(jdbc.update(startsWith("INSERT INTO ai_conversation_message"),
                (Object[]) anyVararg())).thenThrow(new DuplicateKeyException("duplicate"));
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("executionId", 1L);
        stored.put("role", "assistant");
        stored.put("payload", "{\"content\":\"answer\"}");
        when(jdbc.queryForMap(startsWith("SELECT agent_execution_id AS executionId"),
                (Object[]) anyVararg())).thenReturn(stored);

        Map<String, Object> payload = completion("answer");
        assertThat(service.completeRun(LocalAuth.cookieHeader(), payload).get("success"))
                .isEqualTo(true);

        stored.put("payload", "{\"content\":\"different\"}");
        assertThatThrownBy(() -> service.completeRun(LocalAuth.cookieHeader(), payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageId");
    }

    @Test
    public void conversationDeleteSoftDeletesMessagesExecutionsAndConversation() {
        when(jdbc.queryForObject(anyString(), org.mockito.Matchers.eq(Long.class),
                (Object[]) anyVararg())).thenReturn(1L);
        when(jdbc.update(org.mockito.Matchers.contains(
                "UPDATE ai_conversation SET delete_flag=0"),
                (Object[]) anyVararg())).thenReturn(1);

        service.deleteConversation(
                LocalAuth.cookieHeader(),
                Collections.<String, Object>singletonMap("conversationId", "thread-1"));

        verify(jdbc).update(org.mockito.Matchers.contains(
                "UPDATE ai_conversation_message SET delete_flag=0"),
                (Object[]) anyVararg());
        verify(jdbc).update(org.mockito.Matchers.contains(
                "UPDATE ai_agent_execution SET delete_flag=0"),
                (Object[]) anyVararg());
        verify(jdbc).update(org.mockito.Matchers.contains(
                "UPDATE ai_conversation SET delete_flag=0"),
                (Object[]) anyVararg());
    }

    @Test
    public void memoryRecreateContinuesTheDeletedPathsVersion() {
        when(jdbc.queryForList(org.mockito.Matchers.contains("ai_memory_operation"),
                (Object[]) anyVararg())).thenReturn(Collections.<Map<String, Object>>emptyList());
        when(jdbc.queryForList(org.mockito.Matchers.contains("ai_memory_file"),
                (Object[]) anyVararg())).thenReturn(Collections.<Map<String, Object>>emptyList());
        when(jdbc.queryForObject(org.mockito.Matchers.contains("MAX(version)"),
                org.mockito.Matchers.eq(Long.class), (Object[]) anyVararg())).thenReturn(4L);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("path", LocalAuth.USER_ID + "/personal/MEMORY.md");
        payload.put("content", "new value");
        @SuppressWarnings("unchecked")
        Map<String, Object> mutation = (Map<String, Object>) service.memoryWrite(
                LocalAuth.cookieHeader(), payload).get("mutation");

        assertThat(mutation.get("version")).isEqualTo("5");
    }

    private static Map<String, Object> execution(
            Long id, String runId, Long parentId, String parentRunId, String agentName,
            String callId, String task, String status) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("runId", runId);
        value.put("conversationId", "thread-1");
        value.put("parentExecutionId", parentId);
        value.put("parentRunId", parentRunId);
        value.put("agentName", agentName);
        value.put("delegationToolCallId", callId);
        value.put("task", task);
        value.put("status", status);
        value.put("errorCode", null);
        return value;
    }

    private static Map<String, Object> messageRow(
            String id, String role, String payload, String runId, Long parentId) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("messageId", id);
        value.put("role", role);
        value.put("payload", payload);
        value.put("runId", runId);
        value.put("parentExecutionId", parentId);
        return value;
    }

    private static Map<String, Object> completion(String answer) {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("runId", "root-1");
        execution.put("parentRunId", null);
        execution.put("agentName", "UnionCoordinatorAgent");
        execution.put("delegationToolCallId", null);
        execution.put("task", null);
        execution.put("status", "completed");
        execution.put("errorCode", null);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", "message-1");
        message.put("role", "assistant");
        message.put("content", answer);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("runId", "root-1");
        envelope.put("message", message);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", "thread-1");
        payload.put("runId", "root-1");
        payload.put("status", "completed");
        payload.put("errorCode", null);
        payload.put("executions", Collections.singletonList(execution));
        payload.put("messages", Collections.singletonList(envelope));
        return payload;
    }

    private static int count(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
