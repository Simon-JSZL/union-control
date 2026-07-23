package com.union.control;

import com.union.control.controller.AgentController;
import com.union.control.controller.ApiExceptionHandler;
import com.union.control.service.ControlService;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.anyVararg;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

public class ControlServiceTest {
    private static final String COOKIE = "CASSESSIONID=session-1; USERID=user-1";

    private MockMvc mvc;

    @Before
    public void setUp() {
        ControlService service = new ControlService(null, new ObjectMapper());
        mvc = standaloneSetup(new AgentController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    public void rejectsMissingOrMalformedIdentityCookies() throws Exception {
        mvc.perform(post("/agent/conversationCreate")
                .header("Cookie", "CASSESSIONID=session-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"conv-1\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/agent/conversationCreate")
                .header("Cookie", "CASSESSIONID=one; CASSESSIONID=two; USERID=user-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"conv-1\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/agent/conversationCreate")
                .header("Cookie", "CASSESSIONID=session-1,USERID=user-1,")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"conv-1\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/agent/conversationCreate")
                .header("Cookie", "CASSESSIONID=session-1 USERID=user-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"conv-1\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/agent/conversationCreate")
                .header("Cookie", "CASSESSIONID=session-1\tUSERID=user-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"conv-1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void createUsesCookieOwnerAndNeverRequestUserId() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Map<String, Object> stored = metadata("conv-1", "title");
        when(jdbc.queryForMap(anyString(), anyVararg())).thenReturn(stored);
        ControlService dataController = new ControlService(jdbc, new ObjectMapper());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", "conv-1");
        payload.put("title", "title");
        payload.put("userId", "attacker");

        Map<String, Object> response = dataController.create(COOKIE, payload);

        verify(jdbc).update("INSERT INTO ai_conversation (conversation_id,user_id,title) VALUES (?,?,?) " +
                        "ON DUPLICATE KEY UPDATE conversation_id=VALUES(conversation_id)",
                "conv-1", "user-1", "title");
        assertEquals("conv-1", ((Map<String, Object>) response.get("data")).get("conversationId"));
        assertFalse(response.toString().contains("attacker"));
    }

    @Test
    public void conversationListExcludesExpiredRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), anyVararg())).thenReturn(Collections.emptyList());
        ControlService controller = new ControlService(jdbc, new ObjectMapper());

        controller.conversations(COOKIE, true, 100);

        verify(jdbc).query(
                eq("SELECT conversation_id AS conversationId,title,status," +
                        "DATE_FORMAT(expires_at,'%Y-%m-%dT%H:%i:%s') AS expiresAt," +
                        "DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s') AS createdAt," +
                        "DATE_FORMAT(updated_at,'%Y-%m-%dT%H:%i:%s') AS updatedAt FROM ai_conversation" +
                        " WHERE user_id=? AND status<>'expired' " +
                        "AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP) " +
                        "ORDER BY updated_at DESC LIMIT ?"),
                any(RowMapper.class), eq("user-1"), eq(100));
    }

    @Test
    public void expireConversationUpdatesStatusAndTimestampWithoutDeletingRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), (Object[]) anyVararg())).thenReturn(1);
        ControlService controller = new ControlService(jdbc, new ObjectMapper());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", "conv-1");

        controller.expireConversation(COOKIE, payload);

        verify(jdbc).update("UPDATE ai_conversation SET status='expired',expires_at=CURRENT_TIMESTAMP," +
                        "execution_status='idle',execution_token=NULL,execution_started_at=NULL," +
                        "execution_heartbeat_at=NULL,updated_at=CURRENT_TIMESTAMP " +
                        "WHERE conversation_id=? AND user_id=? AND status<>'expired'",
                "conv-1", "user-1");
        verify(jdbc, never()).update(org.mockito.Matchers.startsWith("DELETE"), (Object[]) anyVararg());
    }

    @Test
    public void agentControllerExposesOnlyPythonRoutesWithGetAndPost() throws Exception {
        ControlService service = mock(ControlService.class);
        when(service.items(anyString(), anyString(), org.mockito.Matchers.<Integer>any()))
                .thenReturn(ok(Collections.emptyList()));
        when(service.appendItems(anyString(), anyString(), org.mockito.Matchers.<Map<String, Object>>any()))
                .thenReturn(ok(null));
        when(service.clearItems(anyString(), anyString())).thenReturn(ok(null));
        when(service.popItem(anyString(), anyString())).thenReturn(ok(null));
        when(service.forget(anyString(), org.mockito.Matchers.anyLong())).thenReturn(ok(null));
        mvc = standaloneSetup(new AgentController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();

        mvc.perform(get("/agent/getConversationItems")
                .header("Cookie", COOKIE)
                .param("conversationId", "conv-1"))
                .andExpect(status().isOk());
        mvc.perform(post("/agent/addConversationItems")
                .header("Cookie", COOKIE)
                .param("conversationId", "conv-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"type\":\"message\"}]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/agent/deleteConversationItems")
                .header("Cookie", COOKIE).param("conversationId", "conv-1"))
                .andExpect(status().isOk());
        mvc.perform(post("/agent/popConversationItem")
                .header("Cookie", COOKIE).param("conversationId", "conv-1"))
                .andExpect(status().isOk());
        mvc.perform(post("/agent/memoryDelete")
                .header("Cookie", COOKIE).param("memoryId", "7"))
                .andExpect(status().isOk());

        mvc.perform(get("/agent/conversationDetails").header("Cookie", COOKIE))
                .andExpect(status().isNotFound());
        mvc.perform(get("/agent/executionCurrent").header("Cookie", COOKIE))
                .andExpect(status().isNotFound());
        mvc.perform(get("/agent/authCurrent").header("Cookie", COOKIE))
                .andExpect(status().isNotFound());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void roundTripsRawReasoningItemsWithoutModification() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), anyVararg())).thenReturn(1L, 0L);
        String reasoning = "  first\n第二段  \n";
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "reasoning");
        item.put("reasoning_content", reasoning);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", Collections.singletonList(item));
        ControlService dataController = new ControlService(jdbc, new ObjectMapper());

        dataController.appendItems(COOKIE, "conv-1", payload);

        String expectedJson = new ObjectMapper().writeValueAsString(item);
        verify(jdbc).update(
                "INSERT INTO ai_conversation_message (conversation_id,user_id,seq,conversation_item_json) VALUES (?,?,?,?)",
                "conv-1", "user-1", 1L, expectedJson);
        Map<String, Object> stored = new ObjectMapper().readValue(expectedJson, Map.class);
        assertEquals(reasoning, stored.get("reasoning_content"));
        assertEquals(Arrays.asList("type", "reasoning_content"), Arrays.asList(stored.keySet().toArray()));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void getItemsReturnsLastLimitInOriginalOrder() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), anyVararg())).thenReturn(1L);
        when(jdbc.query(anyString(), any(RowMapper.class), anyVararg())).thenReturn(Arrays.asList(
                "{\"type\":\"reasoning\",\"reasoning_content\":\"a\"}",
                "{\"type\":\"message\",\"role\":\"assistant\",\"content\":\"b\"}"
        ));
        ControlService dataController = new ControlService(jdbc, new ObjectMapper());

        Map<String, Object> response = dataController.items(COOKIE, "conv-1", 2);
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.get("data");

        assertEquals("reasoning", items.get(0).get("type"));
        assertEquals("message", items.get(1).get("type"));
        assertTrue(items.get(0).containsKey("reasoning_content"));
        verify(jdbc).query(
                eq("SELECT recent.conversation_item_json FROM (SELECT seq,conversation_item_json " +
                        "FROM ai_conversation_message WHERE conversation_id=? AND user_id=? " +
                        "AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(conversation_item_json,'$.type')),'')<>'run_error' " +
                        "ORDER BY seq DESC LIMIT ?) recent ORDER BY recent.seq ASC"),
                any(RowMapper.class), eq("conv-1"), eq("user-1"), eq(2));
    }

    @Test
    public void conversationDetailsIncludesRunErrorsButSdkPopSkipsThem() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), anyVararg())).thenReturn(1L);
        when(jdbc.queryForMap(anyString(), anyVararg())).thenReturn(metadata("conv-1", "title"));
        when(jdbc.query(anyString(), any(RowMapper.class), anyVararg())).thenReturn(Collections.emptyList());
        ControlService dataController = new ControlService(jdbc, new ObjectMapper());

        dataController.conversation(COOKIE, "conv-1");
        dataController.popItem(COOKIE, "conv-1");

        verify(jdbc).query(
                eq("SELECT conversation_item_json FROM ai_conversation_message " +
                        "WHERE conversation_id=? AND user_id=? ORDER BY seq ASC"),
                any(RowMapper.class), eq("conv-1"), eq("user-1"));
        verify(jdbc).query(
                eq("SELECT id,conversation_item_json FROM ai_conversation_message WHERE conversation_id=? AND user_id=? " +
                        "AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(conversation_item_json,'$.type')),'')<>'run_error' " +
                        "ORDER BY seq DESC LIMIT 1 FOR UPDATE"),
                any(RowMapper.class), eq("conv-1"), eq("user-1"));
    }

    @Test(expected = IllegalStateException.class)
    public void corruptedStoredSdkItemFailsLoudly() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), anyVararg())).thenReturn(1L);
        when(jdbc.query(anyString(), any(RowMapper.class), anyVararg()))
                .thenReturn(Collections.singletonList("{not-json"));
        ControlService dataController = new ControlService(jdbc, new ObjectMapper());

        dataController.items(COOKIE, "conv-1", null);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void rememberUsesCookieOwnerAndUpsertsSamePersonalKey() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Map<String, Object> stored = memory(7L, "operations", "operations.default_org", "默认查询中国银行");
        when(jdbc.queryForMap(anyString(), anyVararg())).thenReturn(stored);
        ControlService dataController = new ControlService(jdbc, new ObjectMapper());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("domain", "operations");
        payload.put("memoryType", "preference");
        payload.put("memoryKey", "operations.default_org");
        payload.put("content", "默认查询中国银行");

        Map<String, Object> response = dataController.remember(COOKIE, payload);

        verify(jdbc).update("INSERT INTO ai_agent_memory " +
                        "(scope,scope_id,domain,memory_type,memory_key,content,source_conversation_id,status) " +
                        "VALUES ('user',?,?,?,?,?,?,'active') ON DUPLICATE KEY UPDATE " +
                        "domain=VALUES(domain),memory_type=VALUES(memory_type),content=VALUES(content)," +
                        "source_conversation_id=VALUES(source_conversation_id),status='active'," +
                        "updated_at=CURRENT_TIMESTAMP",
                "user-1", "operations", "preference", "operations.default_org", "默认查询中国银行", null);
        assertEquals(7L, ((Map<String, Object>) response.get("data")).get("id"));
        assertFalse(response.toString().contains("scopeId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void listMemoriesReturnsOnlyActivePersonalRowsForCookieOwner() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), anyVararg())).thenReturn(Collections.singletonList(
                memory(7L, "general", "response.language", "默认使用中文")
        ));
        ControlService dataController = new ControlService(jdbc, new ObjectMapper());

        Map<String, Object> response = dataController.memories(COOKIE, 50);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("data");

        assertEquals(1, rows.size());
        verify(jdbc).query(
                eq("SELECT id,domain,memory_type AS memoryType,memory_key AS memoryKey,content," +
                        "source_conversation_id AS sourceConversationId," +
                        "DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s') AS createdAt," +
                        "DATE_FORMAT(updated_at,'%Y-%m-%dT%H:%i:%s') AS updatedAt FROM ai_agent_memory" +
                        " WHERE scope='user' AND scope_id=? AND status='active' " +
                        "ORDER BY updated_at DESC LIMIT ?"),
                any(RowMapper.class), eq("user-1"), eq(50));
    }

    @Test
    public void forgetIsOwnerScopedSoftDelete() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), eq(7L), eq("user-1"))).thenReturn(1);
        ControlService dataController = new ControlService(jdbc, new ObjectMapper());

        dataController.forget(COOKIE, 7L);

        verify(jdbc).update("UPDATE ai_agent_memory SET status='deleted',updated_at=CURRENT_TIMESTAMP " +
                "WHERE id=? AND scope='user' AND scope_id=? AND status='active'", 7L, "user-1");
    }

    @Test(expected = RuntimeException.class)
    public void anotherOwnerCannotForgetMemory() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), eq(7L), eq("user-1"))).thenReturn(0);

        new ControlService(jdbc, new ObjectMapper()).forget(COOKIE, 7L);
    }

    @Test
    public void memoryRejectsClientControlledOwnerAndScope() throws Exception {
        mvc.perform(post("/agent/memorySave")
                .header("Cookie", COOKIE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"domain\":\"general\",\"memoryType\":\"preference\"," +
                        "\"memoryKey\":\"response.language\",\"content\":\"中文\"," +
                        "\"scope\":\"team\",\"scopeId\":\"team-1\",\"userId\":\"attacker\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void memorySourceConversationMustBelongToCookieOwner() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), anyVararg()))
                .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));
        ControlService dataController = new ControlService(jdbc, new ObjectMapper());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("domain", "general");
        payload.put("memoryType", "correction");
        payload.put("memoryKey", "response.language");
        payload.put("content", "默认使用中文");
        payload.put("sourceConversationId", "other-conversation");

        try {
            dataController.remember(COOKIE, payload);
        } catch (RuntimeException expected) {
            verify(jdbc, never()).update(anyString(), (Object[]) anyVararg());
            return;
        }
        throw new AssertionError("应拒绝不属于当前用户的来源会话");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void claimExecutionUsesCookieOwnerAndReturnsRunningState() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), (Object[]) anyVararg())).thenReturn(1, 1);
        when(jdbc.queryForMap(anyString(), anyVararg())).thenReturn(execution("conv-1", "running"));
        ControlService controller = new ControlService(jdbc, new ObjectMapper());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", "conv-1");
        payload.put("executionToken", "token-1");
        payload.put("userId", "attacker");

        Map<String, Object> response = controller.claimExecution(COOKIE, payload);

        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertEquals("conv-1", data.get("conversationId"));
        assertEquals("running", data.get("status"));
        verify(jdbc).update(anyString(), eq("token-1"), eq("conv-1"), eq("user-1"));
    }

    @Test
    public void claimExecutionReturnsStructuredConflictWithoutStartingAnotherRun() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), (Object[]) anyVararg()))
                .thenReturn(Collections.singletonList(execution("other-conv", "running")));
        mvc = standaloneSetup(new AgentController(new ControlService(jdbc, new ObjectMapper())))
                .setControllerAdvice(new ApiExceptionHandler()).build();

        mvc.perform(post("/agent/executionClaim")
                .header("Cookie", COOKIE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"conv-1\",\"executionToken\":\"token-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("agent_run_active"))
                .andExpect(jsonPath("$.activeExecution.conversationId").value("other-conv"))
                .andExpect(jsonPath("$.activeExecution.status").value("running"));
        verify(jdbc, never()).update(anyString(), (Object[]) anyVararg());
    }

    @Test
    public void currentExecutionIsScopedToAuthenticatedUser() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), (Object[]) anyVararg()))
                .thenReturn(Collections.singletonList(execution("conv-1", "running")));
        ControlService service = new ControlService(jdbc, new ObjectMapper());

        service.currentExecution(COOKIE);

        verify(jdbc).queryForList(anyString(), eq("user-1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void cancelExecutionIsOwnerScopedAndIdempotent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), (Object[]) anyVararg())).thenReturn(1);
        when(jdbc.queryForList(anyString(), (Object[]) anyVararg()))
                .thenReturn(Collections.singletonList(execution("conv-1", "cancel_requested")));
        ControlService controller = new ControlService(jdbc, new ObjectMapper());

        Map<String, Object> first = controller.cancelExecution(COOKIE);
        Map<String, Object> second = controller.cancelExecution(COOKIE);

        assertEquals("cancel_requested", ((Map<String, Object>) first.get("data")).get("status"));
        assertEquals("cancel_requested", ((Map<String, Object>) second.get("data")).get("status"));
        verify(jdbc, org.mockito.Mockito.times(4)).update(anyString(), eq("user-1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void heartbeatAndFinishRequireMatchingExecutionToken() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), (Object[]) anyVararg())).thenReturn(1, 1);
        when(jdbc.queryForMap(anyString(), anyVararg())).thenReturn(execution("conv-1", "running"));
        ControlService controller = new ControlService(jdbc, new ObjectMapper());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("executionToken", "token-1");

        Map<String, Object> heartbeat = controller.heartbeatExecution(COOKIE, payload);
        controller.finishExecution(COOKIE, payload);

        assertEquals("running", ((Map<String, Object>) heartbeat.get("data")).get("status"));
        verify(jdbc, org.mockito.Mockito.times(2)).update(anyString(), eq("user-1"), eq("token-1"));
    }

    @Test
    public void staleExecutionTokenCannotClearNewerExecution() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), (Object[]) anyVararg())).thenReturn(0);
        mvc = standaloneSetup(new AgentController(new ControlService(jdbc, new ObjectMapper())))
                .setControllerAdvice(new ApiExceptionHandler()).build();

        mvc.perform(post("/agent/executionFinish")
                .header("Cookie", COOKIE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"executionToken\":\"stale-token\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("agent_execution_stale"));
    }

    @Test
    public void expirationMigrationOnlyAddsMissingColumn() {
        JdbcTemplate missing = mock(JdbcTemplate.class);
        when(missing.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        UnionControlApplication.ensureConversationExpiration(missing);
        verify(missing).execute("ALTER TABLE ai_conversation ADD COLUMN expires_at DATETIME NULL AFTER status");

        JdbcTemplate present = mock(JdbcTemplate.class);
        when(present.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        UnionControlApplication.ensureConversationExpiration(present);
        verify(present, never()).execute(anyString());
    }

    @Test
    public void executionMigrationAddsStateColumnsAndUniqueActiveUserIndex() {
        JdbcTemplate missing = mock(JdbcTemplate.class);
        when(missing.queryForList(anyString(), eq(String.class)))
                .thenReturn(Collections.emptyList(), Collections.emptyList());

        UnionControlApplication.ensureAgentExecutionState(missing);

        verify(missing).execute("ALTER TABLE ai_conversation " +
                "ADD COLUMN execution_status VARCHAR(32) NOT NULL DEFAULT 'idle' AFTER expires_at," +
                "ADD COLUMN execution_token VARCHAR(64) NULL AFTER execution_status," +
                "ADD COLUMN execution_started_at DATETIME NULL AFTER execution_token," +
                "ADD COLUMN execution_heartbeat_at DATETIME NULL AFTER execution_started_at," +
                "ADD COLUMN active_execution_user_id VARCHAR(64) GENERATED ALWAYS AS " +
                "(CASE WHEN execution_status IN ('running','cancel_requested') THEN user_id ELSE NULL END) STORED");
        verify(missing).execute("ALTER TABLE ai_conversation " +
                "ADD UNIQUE KEY uk_active_execution_user (active_execution_user_id)");
        verify(missing).execute("ALTER TABLE ai_conversation " +
                "ADD KEY idx_user_execution_status (user_id,execution_status)");
    }

    @Test
    public void legacyConversationMetadataMigrationDropsUnusedColumns() {
        JdbcTemplate legacy = mock(JdbcTemplate.class);
        when(legacy.queryForList(anyString(), eq(String.class))).thenReturn(Arrays.asList(
                "id", "user_session_id_hash", "summary_text", "summary_upto_seq", "summary_uptoz_seq", "title"));
        UnionControlApplication.dropLegacyConversationMetadata(legacy);
        verify(legacy).execute(
                "ALTER TABLE ai_conversation DROP COLUMN user_session_id_hash," +
                "DROP COLUMN summary_text,DROP COLUMN summary_upto_seq,DROP COLUMN summary_uptoz_seq");

        JdbcTemplate fresh = mock(JdbcTemplate.class);
        when(fresh.queryForList(anyString(), eq(String.class))).thenReturn(Collections.emptyList());
        UnionControlApplication.dropLegacyConversationMetadata(fresh);
        verify(fresh, never()).execute(anyString());
    }

    @Test
    public void agentMemoryMigrationMakesMemoryKeyUserGlobal() {
        JdbcTemplate oldIndex = mock(JdbcTemplate.class);
        when(oldIndex.queryForList(anyString(), eq(String.class))).thenReturn(
                Arrays.asList("scope", "scope_id", "domain", "memory_key"));

        UnionControlApplication.migrateAgentMemoryKeyIndex(oldIndex);

        verify(oldIndex).execute("ALTER TABLE ai_agent_memory DROP INDEX uk_agent_memory_scope_key," +
                "ADD UNIQUE KEY uk_agent_memory_scope_key (scope,scope_id,memory_key)");

        JdbcTemplate currentIndex = mock(JdbcTemplate.class);
        when(currentIndex.queryForList(anyString(), eq(String.class))).thenReturn(
                Arrays.asList("scope", "scope_id", "memory_key"));
        UnionControlApplication.migrateAgentMemoryKeyIndex(currentIndex);
        verify(currentIndex, never()).execute(anyString());
    }

    @Test
    public void conversationMessageMigrationRenamesJsonAndDropsLegacyColumns() {
        JdbcTemplate legacy = mock(JdbcTemplate.class);
        when(legacy.queryForList(anyString(), eq(String.class))).thenReturn(Arrays.asList(
                        "id", "conversation_id", "user_id", "seq", "role", "item_type", "agent_name", "content",
                        "reasoning_content", "tool_name", "call_id", "tool_arguments", "tool_result", "sdk_item_json",
                        "finish_reason", "error_msg", "created_at"),
                Collections.singletonList("idx_conversation_message"));
        when(legacy.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);

        UnionControlApplication.migrateConversationMessages(legacy);

        verify(legacy).execute("ALTER TABLE ai_conversation_message " +
                "CHANGE COLUMN sdk_item_json conversation_item_json JSON NOT NULL," +
                "DROP COLUMN role,DROP COLUMN item_type,DROP COLUMN agent_name,DROP COLUMN content," +
                "DROP COLUMN reasoning_content,DROP COLUMN tool_name,DROP COLUMN call_id," +
                "DROP COLUMN tool_arguments,DROP COLUMN tool_result,DROP COLUMN finish_reason,DROP COLUMN error_msg," +
                "DROP INDEX idx_conversation_message");
    }

    @Test
    public void conversationMessageMigrationDoesNothingForCurrentSchema() {
        JdbcTemplate current = mock(JdbcTemplate.class);
        when(current.queryForList(anyString(), eq(String.class))).thenReturn(Arrays.asList(
                        "id", "conversation_id", "user_id", "seq", "conversation_item_json", "created_at"),
                Arrays.asList("PRIMARY", "uk_conversation_message_seq"));

        UnionControlApplication.migrateConversationMessages(current);

        verify(current, never()).execute(anyString());
    }

    @Test
    public void conversationMessageMigrationFinishesPartialCleanup() {
        JdbcTemplate partial = mock(JdbcTemplate.class);
        when(partial.queryForList(anyString(), eq(String.class))).thenReturn(Arrays.asList(
                        "id", "conversation_id", "user_id", "seq", "role", "conversation_item_json", "created_at"),
                Arrays.asList("PRIMARY", "uk_conversation_message_seq", "idx_conversation_message"));

        UnionControlApplication.migrateConversationMessages(partial);

        verify(partial).execute("ALTER TABLE ai_conversation_message " +
                "DROP COLUMN role,DROP INDEX idx_conversation_message");
    }

    @Test(expected = IllegalStateException.class)
    public void conversationMessageMigrationRefusesToDropRowsMissingJson() {
        JdbcTemplate corrupt = mock(JdbcTemplate.class);
        when(corrupt.queryForList(anyString(), eq(String.class))).thenReturn(Arrays.asList(
                        "id", "conversation_id", "user_id", "seq", "sdk_item_json", "content", "created_at"),
                Arrays.asList("PRIMARY", "uk_conversation_message_seq"));
        when(corrupt.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);

        UnionControlApplication.migrateConversationMessages(corrupt);
    }

    @Test(expected = IllegalStateException.class)
    public void conversationMessageMigrationRefusesUnknownColumns() {
        JdbcTemplate unknown = mock(JdbcTemplate.class);
        when(unknown.queryForList(anyString(), eq(String.class))).thenReturn(Arrays.asList(
                        "id", "conversation_id", "user_id", "seq", "conversation_item_json", "created_at", "mystery"),
                Arrays.asList("PRIMARY", "uk_conversation_message_seq"));

        UnionControlApplication.migrateConversationMessages(unknown);
    }

    private static Map<String, Object> metadata(String conversationId, String title) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("conversationId", conversationId);
        row.put("title", title);
        row.put("status", "active");
        row.put("expiresAt", null);
        row.put("createdAt", "2026-07-14T00:00:00");
        row.put("updatedAt", "2026-07-14T00:00:00");
        return row;
    }

    private static Map<String, Object> memory(long id, String domain, String key, String content) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("domain", domain);
        row.put("memoryType", "preference");
        row.put("memoryKey", key);
        row.put("content", content);
        row.put("sourceConversationId", null);
        row.put("createdAt", "2026-07-17T00:00:00");
        row.put("updatedAt", "2026-07-17T00:00:00");
        return row;
    }

    private static Map<String, Object> execution(String conversationId, String status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("conversationId", conversationId);
        row.put("status", status);
        row.put("startedAt", "2026-07-20T10:00:00");
        row.put("heartbeatAt", "2026-07-20T10:00:01");
        return row;
    }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        return response;
    }

}
