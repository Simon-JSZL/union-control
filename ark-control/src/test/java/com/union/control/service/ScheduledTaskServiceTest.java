package com.union.control.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.TestJson;
import com.union.control.mapper.ScheduledTaskMapper;
import com.union.control.service.ScheduledTaskService;
import com.union.control.schedule.ScheduledExecutionToken;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.contains;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ScheduledTaskServiceTest {
    private ScheduledTaskMapper mapper;
    private JsonScheduledTaskService service;
    private ConversationServiceImpl conversationService;

    @Before
    public void setUp() {
        mapper = mock(ScheduledTaskMapper.class);
        conversationService = mock(ConversationServiceImpl.class);
        service = new JsonScheduledTaskService(
                mapper, new ObjectMapper(), conversationService, 960, 930);
    }

    @Test
    public void timezoneMustBeAZoneInfoCompatibleTzdbIdentifier() {
        assertThat(ScheduledTaskServiceImpl.zone("UTC")).isEqualTo(ZoneId.of("UTC"));
        assertThat(ScheduledTaskServiceImpl.zone("Asia/Shanghai"))
                .isEqualTo(ZoneId.of("Asia/Shanghai"));
        for (String value : Arrays.asList("+08:00", "GMT+08:00", "UTC+08:00")) {
            assertThatThrownBy(() -> ScheduledTaskServiceImpl.zone(value))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timezone");
        }
    }

    @Test
    public void resolvesRawTokenByHashingInsideControl() {
        ScheduledExecutionToken token = ScheduledExecutionToken.issue();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("runId", 7L);
        when(mapper.resolveScheduledToken(token.hash())).thenReturn(row);

        assertThat(service.resolveScheduledToken(
                token.authorizationHeader().substring("Scheduled ".length())))
                .isSameAs(row);
        verify(mapper).resolveScheduledToken(token.hash());
    }

    @Test
    public void calculatesCronAndIntervalAfterTheReferenceInstant() {
        Instant now = Instant.parse("2026-08-10T01:00:01Z");

        assertThat(service.nextCron("0 0 9 * * MON", ZoneId.of("Asia/Shanghai"), now))
                .isEqualTo(Instant.parse("2026-08-17T01:00:00Z"));
        assertThat(service.nextInterval(
                Instant.parse("2026-08-10T00:00:00Z"), 3600, now))
                .isEqualTo(Instant.parse("2026-08-10T02:00:00Z"));
    }

    @Test
    public void rejectsTooFrequentOrInvalidSchedules() {
        assertThatThrownBy(() -> service.nextInterval(Instant.now(), 59, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.validateCron("bad cron", ZoneId.of("UTC")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.validateCron("* * * * * *", ZoneId.of("UTC")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("60");

        service.validateCron("0 * * * * *", ZoneId.of("UTC"));
    }

    @Test
    public void createRejectsMoreThanOneHundredRunnableTasksPerUser() {
        when(mapper.countRunnableTasks(TestJson.USER_ID)).thenReturn(100);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", "超限任务");
        payload.put("prompt", "执行分析");
        payload.put("scheduleType", "ONCE");
        payload.put("runAt", "2030-01-01T00:00:00Z");
        payload.put("timezone", "UTC");

        assertThatThrownBy(() -> service.create(payload))
                .isInstanceOf(ScheduledTaskService.ConflictException.class)
                .hasMessageContaining("100");
        verify(mapper).lockUserTasks(TestJson.USER_ID);
    }

    @Test
    public void createSnapshotsTheAuthenticatedOrganizationAndRole() {
        Map<String, Object> payload = oncePayload();
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked") Map<String, Object> task =
                    (Map<String, Object>) invocation.getArguments()[0];
            task.put("id", 7L);
            return 1;
        }).when(mapper).insertTask(org.mockito.Matchers.<Map<String, Object>>any());
        when(mapper.findTaskDetail(7L, TestJson.USER_ID))
                .thenReturn(Collections.<String, Object>singletonMap("id", 7L));

        service.create(payload);

        ArgumentCaptor<Map> inserted = ArgumentCaptor.forClass(Map.class);
        verify(mapper).insertTask(inserted.capture());
        assertThat(inserted.getValue())
                .containsEntry("userId", TestJson.USER_ID)
                .containsEntry("orgCode", TestJson.ORG_CODE)
                .containsEntry("roleId", TestJson.ROLE_ID);
    }

    @Test
    public void updateValidatesAndPersistsOnlyTheOwnedTaskDefinition() {
        Map<String, Object> owned = new LinkedHashMap<>();
        owned.put("status", "ACTIVE");
        when(mapper.findTaskOwner(7L, TestJson.USER_ID, true)).thenReturn(owned);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", 7L);
        payload.put("title", "新标题");
        payload.put("prompt", "新的可信任务提示");
        payload.put("scheduleType", "ONCE");
        payload.put("runAt", "2099-08-12T15:00:00+08:00");
        payload.put("timezone", "Asia/Shanghai");
        when(mapper.updateTask(org.mockito.Matchers.<Map<String, Object>>any())).thenReturn(1);
        when(mapper.findTaskDetail(7L, TestJson.USER_ID)).thenReturn(Collections.<String, Object>singletonMap("id", 7L));

        service.update(payload);

        ArgumentCaptor<Map> updated = ArgumentCaptor.forClass(Map.class);
        verify(mapper).updateTask(updated.capture());
        assertThat(updated.getValue())
                .containsEntry("taskId", 7L)
                .containsEntry("userId", TestJson.USER_ID)
                .containsEntry("orgCode", TestJson.ORG_CODE)
                .containsEntry("roleId", TestJson.ROLE_ID)
                .containsEntry("title", "新标题")
                .containsEntry("prompt", "新的可信任务提示")
                .containsEntry("scheduleType", "ONCE")
                .containsEntry("timezone", "Asia/Shanghai");
    }

    @Test
    public void updateRejectsTasksNotOwnedByTheCallerBeforeWriting() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", 7L);
        when(mapper.findTaskOwner(7L, TestJson.USER_ID, true)).thenReturn(null);

        assertThatThrownBy(() -> service.update(payload))
                .isInstanceOf(ScheduledTaskService.NotFoundException.class);

        verify(mapper, never()).updateTask(org.mockito.Matchers.<Map<String, Object>>any());
    }

    @Test
    public void runDetailScopesTheQueryToTheAuthenticatedOwner() {
        when(mapper.findOwnedRun(7L, TestJson.USER_ID, false)).thenReturn(null);
        assertThatThrownBy(() -> service.runDetail(7))
                .isInstanceOf(ScheduledTaskService.NotFoundException.class);
        verify(mapper).findOwnedRun(7L, TestJson.USER_ID, false);
    }

    @Test
    public void runDetailSelectsAndReturnsTheStoredResultContent() {
        Map<String, Object> run = run("SUCCEEDED");
        run.put("resultPayload", "{\"content\":\"detail-only\"}");
        when(mapper.findOwnedRun(7L, TestJson.USER_ID, false)).thenReturn(run);

        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>)
                service.runDetail(7).get("data");

        assertThat(data).containsEntry("resultContent", "detail-only")
                .doesNotContainKey("resultPayload");
    }

    @Test
    public void unreadAcceptsMysqlBooleanReadFlag() {
        Map<String, Object> run = run("SUCCEEDED");
        run.put("readFlag", Boolean.FALSE);
        run.put("resultPayload", "{\"content\":\"must-not-be-listed\"}");
        when(mapper.countUnread(TestJson.USER_ID)).thenReturn(1L);
        when(mapper.findUnread(TestJson.USER_ID)).thenReturn(Collections.singletonList(run));

        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>)
                service.unread().get("data");
        @SuppressWarnings("unchecked") List<Map<String, Object>> items =
                (List<Map<String, Object>>) data.get("items");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("readFlag")).isEqualTo(false);
        assertThat(items.get(0)).doesNotContainKeys("resultPayload", "resultContent");
        verify(mapper).findUnread(TestJson.USER_ID);
    }

    @Test
    public void runListDoesNotSelectOrExposeResultPayload() {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", 3L);
        when(mapper.findTaskOwner(3L, TestJson.USER_ID, false)).thenReturn(task);
        when(mapper.countRuns(3L, TestJson.USER_ID)).thenReturn(1L);
        Map<String, Object> run = run("SUCCEEDED");
        run.put("resultPayload", "{\"content\":\"must-not-be-listed\"}");
        when(mapper.findRuns(3L, TestJson.USER_ID, 20, 0))
                .thenReturn(Collections.singletonList(run));

        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>)
                service.runs(3, 1, 20).get("data");
        @SuppressWarnings("unchecked") List<Map<String, Object>> items =
                (List<Map<String, Object>>) data.get("items");

        assertThat(items).hasSize(1);
        assertThat(items.get(0)).doesNotContainKeys("resultPayload", "resultContent");
        verify(mapper).findRuns(3L, TestJson.USER_ID, 20, 0);
    }

    @Test
    public void openingAnAlreadyMaterializedRunIsIdempotent() {
        Map<String, Object> run = run("SUCCEEDED");
        run.put("resultConversationId", "scheduled-7");
        when(mapper.findOwnedRun(7L, TestJson.USER_ID, true)).thenReturn(run);

        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>)
                service.open(7).get("data");

        assertThat(data.get("conversationId")).isEqualTo("scheduled-7");
        verify(mapper).markRunRead(7L);
        verify(conversationService, never()).materializeCompletedConversation(
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    public void onlyTerminalRunsCanBeOpened() {
        for (String status : Arrays.asList("PENDING", "RUNNING")) {
            when(mapper.findOwnedRun(7L, TestJson.USER_ID, true)).thenReturn(run(status));

            assertThatThrownBy(() -> service.open(7))
                    .isInstanceOf(ScheduledTaskService.ConflictException.class)
                    .hasMessageContaining("尚未结束");
        }
        verify(conversationService, never()).materializeCompletedConversation(
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    public void openingScopesTheLockedRunToTheAuthenticatedOwner() {
        when(mapper.findOwnedRun(7L, TestJson.USER_ID, true)).thenReturn(null);

        assertThatThrownBy(() -> service.open(7))
                .isInstanceOf(ScheduledTaskService.NotFoundException.class);

        verify(mapper).findOwnedRun(7L, TestJson.USER_ID, true);
        verify(conversationService, never()).materializeCompletedConversation(
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    public void openingCreatesTheExistingAguiConversationModelOnce() throws Exception {
        Map<String, Object> run = run("SUCCEEDED");
        run.put("title", "日报");
        run.put("prompt", "可信任务提示");
        run.put("resultPayload", new ObjectMapper().writeValueAsString(result()));
        when(mapper.findOwnedRun(7L, TestJson.USER_ID, true)).thenReturn(run);
        when(conversationService.materializeCompletedConversation(anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn("scheduled-7-shared");

        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>)
                service.open(7).get("data");

        assertThat(data.get("conversationId")).isEqualTo("scheduled-7-shared");
        verify(conversationService).materializeCompletedConversation(
                TestJson.USER_ID, "日报", "可信任务提示", "日报内容", "ScheduledTaskAgent");
        verify(mapper).attachConversation(7L, "scheduled-7-shared");
    }

    @Test
    public void openingAFailedRunCreatesAFollowUpConversationWithTheSafeFailure() {
        Map<String, Object> run = run("FAILED");
        run.put("title", "失败任务");
        run.put("prompt", "执行可信任务");
        run.put("errorMessage", "定时任务执行失败");
        when(mapper.findOwnedRun(7L, TestJson.USER_ID, true)).thenReturn(run);
        when(conversationService.materializeCompletedConversation(anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn("scheduled-7-failed");

        service.open(7);

        verify(conversationService).materializeCompletedConversation(
                TestJson.USER_ID, "失败任务", "执行可信任务", "定时任务执行失败", "ScheduledTaskAgent");
        verify(mapper).attachConversation(7L, "scheduled-7-failed");
    }

    @Test
    public void claimUsesSkipLockedAndSchemaEnforcesOccurrenceDeduplication() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/resources/mapper/ScheduledTaskMapper.xml")),
                StandardCharsets.UTF_8);
        String schema = new String(Files.readAllBytes(Paths.get(
                "src/main/resources/schema.sql")), StandardCharsets.UTF_8);

        assertThat(source).contains("FOR UPDATE SKIP LOCKED");
        assertThat(source).contains("r.status IN ('SUCCEEDED','FAILED') AND r.read_flag=0");
        assertThat(schema).contains(
                "UNIQUE KEY `uk_scheduled_run_occurrence` (`task_id`, `scheduled_at`)",
                "`role_id` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL");
    }

    @Test
    public void claimingAOnceTaskExhaustsItBeforeTheRunIsDispatched() {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", 3L);
        task.put("scheduleType", "ONCE");
        task.put("nextRunAt", java.sql.Timestamp.from(Instant.parse("2026-08-11T00:00:00Z")));
        when(mapper.findDueTask()).thenReturn(task);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked") Map<String, Object> run =
                    (Map<String, Object>) invocation.getArguments()[0];
            run.put("id", 7L);
            return 1;
        }).when(mapper).insertRun(org.mockito.Matchers.<Map<String, Object>>any());

        assertThat(service.claimDueRun()).isEqualTo(7L);

        verify(mapper).completeOnceTask(3L);
    }

    @Test
    public void convertsMysqlLocalDateTimeAsUtc() {
        assertThat(ScheduledTaskServiceImpl.instant(
                LocalDateTime.of(2026, 8, 10, 16, 45, 49, 780000000)))
                .isEqualTo(Instant.parse("2026-08-10T16:45:49.780Z"));
    }

    @Test
    public void writesDatabaseDateTimesAsUtcLiteralsWithoutJvmTimezoneConversion() {
        assertThat(ScheduledTaskServiceImpl.databaseDateTime(
                Instant.parse("2026-08-11T04:52:30Z")))
                .isEqualTo("2026-08-11 04:52:30.000");
    }

    @Test
    public void scheduledMapperNeverOwnsNormalConversationPersistence() throws Exception {
        String scheduledMapper = new String(Files.readAllBytes(Paths.get(
                "src/main/resources/mapper/ScheduledTaskMapper.xml")),
                StandardCharsets.UTF_8);
        assertThat(scheduledMapper)
                .doesNotContain("INSERT INTO ai_conversation")
                .doesNotContain("INSERT INTO ai_agent_execution")
                .doesNotContain("INSERT INTO ai_conversation_message")
                .contains("SET result_conversation_id=#{conversationId},read_flag=1");
    }

    @Test
    public void storesOnlyTheResultFieldsNeededToOpenAConversation() throws Exception {
        Map<String, Object> run = run("RUNNING");
        when(mapper.findRunForUpdate(7L)).thenReturn(run);
        Map<String, Object> result = result();
        result.put("providerSecret", "must-not-be-stored");
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);

        service.completeFromProxy(7, result);

        verify(mapper).completeRun(eq(7L), stored.capture());
        String storedJson = stored.getValue();
        @SuppressWarnings("unchecked") Map<String, Object> decoded =
                new ObjectMapper().readValue(storedJson, Map.class);
        assertThat(decoded).containsEntry("content", "日报内容")
                .containsEntry("agentName", "ScheduledTaskAgent")
                .doesNotContainKeys("messages", "providerSecret");
    }

    private static Map<String, Object> run(String status) {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("id", 7L);
        run.put("taskId", 3L);
        run.put("status", status);
        run.put("readFlag", 0);
        run.put("resultConversationId", null);
        return run;
    }

    private static Map<String, Object> oncePayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", "日报任务");
        payload.put("prompt", "生成日报");
        payload.put("scheduleType", "ONCE");
        payload.put("runAt", "2099-01-01T00:00:00Z");
        payload.put("timezone", "UTC");
        return payload;
    }

    private static Map<String, Object> result() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("id", "user-1");
        first.put("role", "user");
        first.put("content", "生成日报");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("id", "assistant-1");
        second.put("role", "assistant");
        second.put("content", "日报内容");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", "日报内容");
        result.put("agentName", "ScheduledTaskAgent");
        result.put("messages", Arrays.asList(first, second));
        return result;
    }
    private static final class JsonScheduledTaskService extends ScheduledTaskServiceImpl {
        JsonScheduledTaskService(ScheduledTaskMapper mapper, ObjectMapper json,
                ConversationServiceImpl conversations, int ignoredTtl, int ignoredMax) {
            super(mapper, json, conversations);
        }

        Map<String, Object> create(Map<String, Object> value) {
            return super.create(TestJson.request(value));
        }

        Map<String, Object> update(Map<String, Object> value) {
            return super.update(TestJson.request(value));
        }

        Map<String, Object> list(String keyword, String status, int page, int pageSize) {
            return super.list(TestJson.request("keyword", keyword, "status", status,
                    "page", page, "pageSize", pageSize));
        }

        Map<String, Object> detail(long taskId) {
            return super.detail(TestJson.request("taskId", taskId));
        }

        Map<String, Object> runs(long taskId, int page, int pageSize) {
            return super.runs(TestJson.request("taskId", taskId, "page", page,
                    "pageSize", pageSize));
        }

        Map<String, Object> runDetail(long runId) {
            return super.runDetail(TestJson.request("runId", runId));
        }

        Map<String, Object> unread() {
            return super.unread(TestJson.request());
        }

        Map<String, Object> start(long taskId) {
            return super.start(TestJson.request("taskId", taskId));
        }

        Map<String, Object> pause(long taskId) {
            return super.pause(TestJson.request("taskId", taskId));
        }

        Map<String, Object> discard(long taskId) {
            return super.discard(TestJson.request("taskId", taskId));
        }

        Map<String, Object> open(long runId) {
            return super.open(TestJson.request("runId", runId));
        }
    }

}
