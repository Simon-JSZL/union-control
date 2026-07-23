package com.union.control.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class ControlService {
    private static final Logger LOG = LoggerFactory.getLogger(ControlService.class);
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:@-]{1,64}");
    private static final Pattern USER_ID = Pattern.compile("[A-Za-z0-9._:@-]{1,64}");
    private static final Pattern MEMORY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern COOKIE_VALUE = Pattern.compile("[\\x21\\x23-\\x2B\\x2D-\\x3A\\x3C-\\x5B\\x5D-\\x7E]{1,4096}");
    private static final List<String> MEMORY_DOMAINS = Arrays.asList(
            "general", "knowledge", "operations", "diagnosis", "behavior_risk");
    private static final List<String> MEMORY_TYPES = Arrays.asList(
            "preference", "correction", "workflow", "terminology");
    private static final String META_SELECT =
            "SELECT conversation_id AS conversationId,title,status," +
            "DATE_FORMAT(expires_at,'%Y-%m-%dT%H:%i:%s') AS expiresAt," +
            "DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s') AS createdAt," +
            "DATE_FORMAT(updated_at,'%Y-%m-%dT%H:%i:%s') AS updatedAt FROM ai_conversation";
    private static final String MEMORY_SELECT =
            "SELECT id,domain,memory_type AS memoryType,memory_key AS memoryKey,content," +
            "source_conversation_id AS sourceConversationId," +
            "DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s') AS createdAt," +
            "DATE_FORMAT(updated_at,'%Y-%m-%dT%H:%i:%s') AS updatedAt FROM ai_agent_memory";
    private static final String EXECUTION_SELECT =
            "SELECT conversation_id AS conversationId,execution_status AS status," +
            "DATE_FORMAT(execution_started_at,'%Y-%m-%dT%H:%i:%s') AS startedAt," +
            "DATE_FORMAT(execution_heartbeat_at,'%Y-%m-%dT%H:%i:%s') AS heartbeatAt " +
            "FROM ai_conversation";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ControlService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Map<String, Object> create(String cookieHeader, Map<String, Object> payload) {
        Identity identity = identity(cookieHeader);
        String conversationId = text(payload, "conversationId", 64, true);
        requireId(conversationId);
        String title = text(payload, "title", 255, false);
        LOG.info("MySQL conversation.create");
        jdbc.update("INSERT INTO ai_conversation (conversation_id,user_id,title) VALUES (?,?,?) " +
                        "ON DUPLICATE KEY UPDATE conversation_id=VALUES(conversation_id)",
                conversationId, identity.userId, title);
        requireActive(identity.userId, conversationId, false);
        return ok(loadConversation(identity.userId, conversationId));
    }

    public Map<String, Object> conversations(
            String cookieHeader, boolean includeArchived, int limit) {
        Identity identity = identity(cookieHeader);
        requireRange(limit, 1, 100, "limit");
        String sql = META_SELECT + " WHERE user_id=? AND status<>'expired' " +
                "AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP) " +
                (includeArchived ? "" : "AND status='active' ") + "ORDER BY updated_at DESC LIMIT ?";
        LOG.info("MySQL conversation.list limit={} includeArchived={}", limit, includeArchived);
        List<Map<String, Object>> rows = jdbc.query(sql, (rs, rowNum) -> metadata(rs), identity.userId, limit);
        return ok(rows);
    }

    public Map<String, Object> conversation(
            String cookieHeader, String conversationId) {
        Identity identity = identity(cookieHeader);
        requireId(conversationId);
        requireActive(identity.userId, conversationId, false);
        Map<String, Object> result = loadConversation(identity.userId, conversationId);
        result.put("items", loadItems(identity.userId, conversationId, null, true));
        return ok(result);
    }

    public Map<String, Object> items(
            String cookieHeader, String conversationId, Integer limit) {
        Identity identity = identity(cookieHeader);
        requireId(conversationId);
        if (limit != null) requireRange(limit, 1, 1000, "limit");
        requireActive(identity.userId, conversationId, false);
        LOG.info("MySQL conversation.items limit={}", limit == null ? "all" : limit);
        return ok(loadItems(identity.userId, conversationId, limit, false));
    }

    @Transactional
    public Map<String, Object> appendItems(
            String cookieHeader, String conversationId, Map<String, Object> payload) {
        Identity identity = identity(cookieHeader);
        requireId(conversationId);
        Object rawItems = payload.get("items");
        if (!(rawItems instanceof List) || ((List<?>) rawItems).isEmpty() || ((List<?>) rawItems).size() > 100)
            throw new IllegalArgumentException("items 必须包含 1 到 100 条 SDK item");
        List<String> items = new ArrayList<>();
        for (Object item : (List<?>) rawItems) items.add(serializeItem(item));

        requireActive(identity.userId, conversationId, true);
        Long current = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq),0) FROM ai_conversation_message WHERE conversation_id=? AND user_id=?",
                Long.class, conversationId, identity.userId);
        long seq = current == null ? 0 : current;
        LOG.info("MySQL conversation.items.append count={}", items.size());
        for (String item : items) {
            jdbc.update("INSERT INTO ai_conversation_message (conversation_id,user_id,seq,conversation_item_json) VALUES (?,?,?,?)",
                    conversationId, identity.userId, ++seq, item);
        }
        jdbc.update("UPDATE ai_conversation SET updated_at=CURRENT_TIMESTAMP WHERE conversation_id=? AND user_id=?",
                conversationId, identity.userId);
        return ok(null);
    }

    @Transactional
    public Map<String, Object> popItem(
            String cookieHeader, String conversationId) {
        Identity identity = identity(cookieHeader);
        requireId(conversationId);
        requireActive(identity.userId, conversationId, true);
        List<StoredItem> rows = jdbc.query(
                "SELECT id,conversation_item_json FROM ai_conversation_message WHERE conversation_id=? AND user_id=? " +
                        "AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(conversation_item_json,'$.type')),'')<>'run_error' " +
                        "ORDER BY seq DESC LIMIT 1 FOR UPDATE",
                (rs, rowNum) -> new StoredItem(rs.getLong("id"), rs.getString("conversation_item_json")),
                conversationId, identity.userId);
        if (rows.isEmpty()) return ok(null);
        Object item = parseItem(rows.get(0).json);
        jdbc.update("DELETE FROM ai_conversation_message WHERE id=?", rows.get(0).id);
        jdbc.update("UPDATE ai_conversation SET updated_at=CURRENT_TIMESTAMP WHERE conversation_id=? AND user_id=?",
                conversationId, identity.userId);
        LOG.info("MySQL conversation.items.pop");
        return ok(item);
    }

    @Transactional
    public Map<String, Object> clearItems(
            String cookieHeader, String conversationId) {
        Identity identity = identity(cookieHeader);
        requireId(conversationId);
        requireActive(identity.userId, conversationId, true);
        jdbc.update("DELETE FROM ai_conversation_message WHERE conversation_id=? AND user_id=?",
                conversationId, identity.userId);
        jdbc.update("UPDATE ai_conversation SET updated_at=CURRENT_TIMESTAMP WHERE conversation_id=? AND user_id=?",
                conversationId, identity.userId);
        LOG.info("MySQL conversation.items.clear");
        return ok(null);
    }

    public Map<String, Object> rename(
            String cookieHeader, Map<String, Object> payload) {
        Identity identity = identity(cookieHeader);
        String conversationId = text(payload, "conversationId", 64, true);
        requireId(conversationId);
        String title = text(payload, "title", 255, true);
        requireOwned(identity.userId, conversationId, false);
        jdbc.update("UPDATE ai_conversation SET title=?,updated_at=CURRENT_TIMESTAMP WHERE conversation_id=? AND user_id=?",
                title, conversationId, identity.userId);
        LOG.info("MySQL conversation.rename");
        return ok(loadConversation(identity.userId, conversationId));
    }

    public Map<String, Object> restore(
            String cookieHeader, Map<String, Object> payload) {
        Identity identity = identity(cookieHeader);
        String conversationId = text(payload, "conversationId", 64, true);
        requireId(conversationId);
        int updated = jdbc.update("UPDATE ai_conversation SET status='active',updated_at=CURRENT_TIMESTAMP " +
                        "WHERE conversation_id=? AND user_id=? AND status='archived' " +
                        "AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)",
                conversationId, identity.userId);
        if (updated != 1) throw new NotFoundException("会话不存在、未归档或已过期");
        LOG.info("MySQL conversation.restore");
        return ok(loadConversation(identity.userId, conversationId));
    }

    public Map<String, Object> expireConversation(
            String cookieHeader, Map<String, Object> payload) {
        Identity identity = identity(cookieHeader);
        String conversationId = text(payload, "conversationId", 64, true);
        requireId(conversationId);
        int updated = jdbc.update("UPDATE ai_conversation SET status='expired',expires_at=CURRENT_TIMESTAMP," +
                        "execution_status='idle',execution_token=NULL,execution_started_at=NULL," +
                        "execution_heartbeat_at=NULL,updated_at=CURRENT_TIMESTAMP " +
                        "WHERE conversation_id=? AND user_id=? AND status<>'expired'",
                conversationId, identity.userId);
        if (updated != 1) throw new NotFoundException("会话不存在或已过期");
        LOG.info("MySQL conversation.expire");
        return ok(null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> claimExecution(
            String cookieHeader, Map<String, Object> payload) {
        Identity identity = identity(cookieHeader);
        String conversationId = text(payload, "conversationId", 64, true);
        String executionToken = text(payload, "executionToken", 64, true);
        requireId(conversationId);
        requireExecutionToken(executionToken);
        String title = text(payload, "title", 255, false);
        Map<String, Object> active = loadCurrentExecution(identity.userId);
        if (active != null) throw new ActiveExecutionException(active);
        jdbc.update("INSERT INTO ai_conversation (conversation_id,user_id,title) VALUES (?,?,?) " +
                        "ON DUPLICATE KEY UPDATE conversation_id=VALUES(conversation_id)",
                conversationId, identity.userId, title);
        try {
            int updated = jdbc.update("UPDATE ai_conversation SET execution_status='running'," +
                            "execution_token=?,execution_started_at=CURRENT_TIMESTAMP," +
                            "execution_heartbeat_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP " +
                            "WHERE conversation_id=? AND user_id=? AND status='active' " +
                            "AND execution_status='idle' " +
                            "AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)",
                    executionToken, conversationId, identity.userId);
            if (updated == 1) {
                LOG.info("Agent execution.claim conversation_id={}", conversationId);
                return ok(loadExecutionByToken(identity.userId, executionToken));
            }
        } catch (DuplicateKeyException e) {
            LOG.info("Agent execution.claim conflict conversation_id={}", conversationId);
        }
        active = loadCurrentExecution(identity.userId);
        if (active != null) throw new ActiveExecutionException(active);
        throw new NotFoundException("会话不存在、未激活或已过期");
    }

    public Map<String, Object> currentExecution(
            String cookieHeader) {
        Identity identity = identity(cookieHeader);
        clearStaleCancelledExecution(identity.userId);
        return ok(loadCurrentExecution(identity.userId));
    }

    public Map<String, Object> heartbeatExecution(
            String cookieHeader, Map<String, Object> payload) {
        Identity identity = identity(cookieHeader);
        String token = executionToken(payload);
        int updated = jdbc.update("UPDATE ai_conversation SET execution_heartbeat_at=CURRENT_TIMESTAMP " +
                        "WHERE user_id=? AND execution_token=? " +
                        "AND execution_status IN ('running','cancel_requested')",
                identity.userId, token);
        if (updated != 1) throw new StaleExecutionException();
        return ok(loadExecutionByToken(identity.userId, token));
    }

    public Map<String, Object> cancelExecution(
            String cookieHeader) {
        Identity identity = identity(cookieHeader);
        jdbc.update("UPDATE ai_conversation SET execution_status='cancel_requested' " +
                        "WHERE user_id=? AND execution_status='running'",
                identity.userId);
        clearStaleCancelledExecution(identity.userId);
        Map<String, Object> active = loadCurrentExecution(identity.userId);
        LOG.info("Agent execution.cancel requested={}", active != null);
        return ok(active);
    }

    public Map<String, Object> finishExecution(
            String cookieHeader, Map<String, Object> payload) {
        Identity identity = identity(cookieHeader);
        String token = executionToken(payload);
        int updated = jdbc.update("UPDATE ai_conversation SET execution_status='idle',execution_token=NULL," +
                        "execution_started_at=NULL,execution_heartbeat_at=NULL,updated_at=CURRENT_TIMESTAMP " +
                        "WHERE user_id=? AND execution_token=? " +
                        "AND execution_status IN ('running','cancel_requested')",
                identity.userId, token);
        if (updated != 1) throw new StaleExecutionException();
        LOG.info("Agent execution.finish");
        return ok(null);
    }

    public Map<String, Object> memories(
            String cookieHeader, int limit) {
        Identity identity = identity(cookieHeader);
        requireRange(limit, 1, 100, "limit");
        LOG.info("MySQL agent.memory.list limit={}", limit);
        List<Map<String, Object>> rows = jdbc.query(
                MEMORY_SELECT + " WHERE scope='user' AND scope_id=? AND status='active' " +
                        "ORDER BY updated_at DESC LIMIT ?",
                (rs, rowNum) -> memory(rs), identity.userId, limit);
        return ok(rows);
    }

    @Transactional
    public Map<String, Object> remember(
            String cookieHeader, Map<String, Object> payload) {
        Identity identity = identity(cookieHeader);
        rejectClientOwnedMemoryFields(payload);
        String domain = text(payload, "domain", 32, true);
        String memoryType = text(payload, "memoryType", 32, true);
        String memoryKey = text(payload, "memoryKey", 128, true);
        String content = text(payload, "content", 2000, true);
        String sourceConversationId = text(payload, "sourceConversationId", 64, false);
        if (!MEMORY_DOMAINS.contains(domain)) throw new IllegalArgumentException("domain 非法");
        if (!MEMORY_TYPES.contains(memoryType)) throw new IllegalArgumentException("memoryType 非法");
        if (!MEMORY_KEY.matcher(memoryKey).matches()) throw new IllegalArgumentException("memoryKey 非法");
        if (sourceConversationId != null) {
            requireId(sourceConversationId);
            requireOwned(identity.userId, sourceConversationId, false);
        }
        jdbc.update("INSERT INTO ai_agent_memory " +
                        "(scope,scope_id,domain,memory_type,memory_key,content,source_conversation_id,status) " +
                        "VALUES ('user',?,?,?,?,?,?,'active') ON DUPLICATE KEY UPDATE " +
                        "domain=VALUES(domain),memory_type=VALUES(memory_type),content=VALUES(content)," +
                        "source_conversation_id=VALUES(source_conversation_id),status='active'," +
                        "updated_at=CURRENT_TIMESTAMP",
                identity.userId, domain, memoryType, memoryKey, content, sourceConversationId);
        LOG.info("MySQL agent.memory.upsert domain={} type={}", domain, memoryType);
        return ok(loadMemory(identity.userId, memoryKey));
    }

    public Map<String, Object> forget(
            String cookieHeader, long memoryId) {
        Identity identity = identity(cookieHeader);
        if (memoryId < 1) throw new IllegalArgumentException("memoryId 非法");
        int updated = jdbc.update(
                "UPDATE ai_agent_memory SET status='deleted',updated_at=CURRENT_TIMESTAMP " +
                        "WHERE id=? AND scope='user' AND scope_id=? AND status='active'",
                memoryId, identity.userId);
        if (updated != 1) throw new NotFoundException("个人记忆不存在");
        LOG.info("MySQL agent.memory.delete");
        return ok(null);
    }

    private Map<String, Object> loadConversation(String userId, String conversationId) {
        try {
            return jdbc.queryForMap(META_SELECT + " WHERE conversation_id=? AND user_id=?", conversationId, userId);
        } catch (EmptyResultDataAccessException e) {
            throw new NotFoundException("会话不存在");
        }
    }

    private Map<String, Object> loadMemory(String userId, String memoryKey) {
        try {
            return jdbc.queryForMap(MEMORY_SELECT +
                            " WHERE scope='user' AND scope_id=? AND memory_key=? AND status='active'",
                    userId, memoryKey);
        } catch (EmptyResultDataAccessException e) {
            throw new NotFoundException("个人记忆不存在");
        }
    }

    private Map<String, Object> loadCurrentExecution(String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                EXECUTION_SELECT + " WHERE user_id=? " +
                        "AND execution_status IN ('running','cancel_requested') LIMIT 1",
                userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void clearStaleCancelledExecution(String userId) {
        jdbc.update("UPDATE ai_conversation SET execution_status='idle',execution_token=NULL," +
                        "execution_started_at=NULL,execution_heartbeat_at=NULL,updated_at=CURRENT_TIMESTAMP " +
                        "WHERE user_id=? AND execution_status='cancel_requested' " +
                        "AND execution_heartbeat_at<DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 30 SECOND)",
                userId);
    }

    private Map<String, Object> loadExecutionByToken(String userId, String token) {
        try {
            return jdbc.queryForMap(EXECUTION_SELECT + " WHERE user_id=? AND execution_token=? " +
                            "AND execution_status IN ('running','cancel_requested')",
                    userId, token);
        } catch (EmptyResultDataAccessException e) {
            throw new StaleExecutionException();
        }
    }

    private List<Object> loadItems(String userId, String conversationId, Integer limit, boolean includeRunErrors) {
        String typeFilter = includeRunErrors ? "" :
                "AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(conversation_item_json,'$.type')),'')<>'run_error' ";
        List<String> stored;
        if (limit == null) {
            stored = jdbc.query("SELECT conversation_item_json FROM ai_conversation_message " +
                            "WHERE conversation_id=? AND user_id=? " + typeFilter + "ORDER BY seq ASC",
                    (rs, rowNum) -> rs.getString("conversation_item_json"), conversationId, userId);
        } else {
            stored = jdbc.query("SELECT recent.conversation_item_json FROM (SELECT seq,conversation_item_json " +
                            "FROM ai_conversation_message WHERE conversation_id=? AND user_id=? " + typeFilter +
                            "ORDER BY seq DESC LIMIT ?) recent ORDER BY recent.seq ASC",
                    (rs, rowNum) -> rs.getString("conversation_item_json"), conversationId, userId, limit);
        }
        List<Object> items = new ArrayList<>();
        for (String value : stored) items.add(parseItem(value));
        return items;
    }

    private String serializeItem(Object item) {
        if (!(item instanceof Map)) throw new IllegalArgumentException("SDK item 必须是 JSON object");
        try {
            return json.writeValueAsString(item);
        } catch (Exception e) {
            throw new IllegalArgumentException("SDK item 不是有效 JSON");
        }
    }

    private Object parseItem(String value) {
        if (value == null) throw new DataCorruptionException();
        try {
            Object item = json.readValue(value, new TypeReference<Object>() {});
            if (!(item instanceof Map)) throw new DataCorruptionException();
            return item;
        } catch (DataCorruptionException e) {
            throw e;
        } catch (Exception e) {
            throw new DataCorruptionException();
        }
    }

    private void requireActive(String userId, String conversationId, boolean lock) {
        try {
            jdbc.queryForObject("SELECT id FROM ai_conversation WHERE conversation_id=? AND user_id=? " +
                            "AND status='active' AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)" +
                            (lock ? " FOR UPDATE" : ""),
                    Long.class, conversationId, userId);
        } catch (EmptyResultDataAccessException e) {
            throw new NotFoundException("会话不存在、未激活或已过期");
        }
    }

    private void requireOwned(String userId, String conversationId, boolean lock) {
        try {
            jdbc.queryForObject("SELECT id FROM ai_conversation WHERE conversation_id=? AND user_id=?" +
                            (lock ? " FOR UPDATE" : ""),
                    Long.class, conversationId, userId);
        } catch (EmptyResultDataAccessException e) {
            throw new NotFoundException("会话不存在");
        }
    }

    private static Map<String, Object> metadata(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("conversationId", rs.getString("conversationId"));
        row.put("title", rs.getString("title"));
        row.put("status", rs.getString("status"));
        row.put("expiresAt", rs.getString("expiresAt"));
        row.put("createdAt", rs.getString("createdAt"));
        row.put("updatedAt", rs.getString("updatedAt"));
        return row;
    }

    private static Map<String, Object> memory(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("domain", rs.getString("domain"));
        row.put("memoryType", rs.getString("memoryType"));
        row.put("memoryKey", rs.getString("memoryKey"));
        row.put("content", rs.getString("content"));
        row.put("sourceConversationId", rs.getString("sourceConversationId"));
        row.put("createdAt", rs.getString("createdAt"));
        row.put("updatedAt", rs.getString("updatedAt"));
        return row;
    }

    private static Identity identity(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.length() > 8192 || cookieHeader.indexOf('\r') >= 0 ||
                cookieHeader.indexOf('\n') >= 0) throw new UnauthorizedException();
        String cas = null;
        String userId = null;
        for (String raw : cookieHeader.split(";", -1)) {
            String part = raw.trim();
            if (part.isEmpty()) continue;
            int separator = part.indexOf('=');
            if (separator < 1) throw new UnauthorizedException();
            String name = part.substring(0, separator).trim();
            String value = part.substring(separator + 1).trim();
            if ("CASSESSIONID".equals(name)) {
                if (cas != null || !COOKIE_VALUE.matcher(value).matches()) throw new UnauthorizedException();
                cas = value;
            } else if ("USERID".equals(name)) {
                if (userId != null || !USER_ID.matcher(value).matches()) throw new UnauthorizedException();
                userId = value;
            }
        }
        if (!LocalAuth.CAS_SESSION_ID.equals(cas) || !LocalAuth.USER_ID.equals(userId))
            throw new UnauthorizedException();
        return new Identity(LocalAuth.USER_ID);
    }

    private static void requireId(String value) {
        if (value == null || !ID.matcher(value).matches()) throw new IllegalArgumentException("conversationId 非法");
    }

    private static String executionToken(Map<String, Object> payload) {
        String token = text(payload, "executionToken", 64, true);
        requireExecutionToken(token);
        return token;
    }

    private static void requireExecutionToken(String value) {
        if (value == null || !ID.matcher(value).matches())
            throw new IllegalArgumentException("executionToken 非法");
    }

    private static void requireRange(int value, int min, int max, String name) {
        if (value < min || value > max) throw new IllegalArgumentException(name + " 超出范围");
    }

    private static void rejectClientOwnedMemoryFields(Map<String, Object> payload) {
        for (String field : Arrays.asList("id", "scope", "scopeId", "userId", "status")) {
            if (payload.containsKey(field)) throw new IllegalArgumentException(field + " 不允许由请求指定");
        }
    }

    private static String text(Map<String, Object> values, String key, int max, boolean required) {
        Object raw = values.get(key);
        if (raw != null && !(raw instanceof String)) throw new IllegalArgumentException(key + " 非法");
        String value = raw == null ? null : ((String) raw).trim();
        if (value != null && value.isEmpty()) value = null;
        if ((required && value == null) || (value != null && value.length() > max))
            throw new IllegalArgumentException(key + " 非法");
        return value;
    }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        return response;
    }

    private static class Identity {
        private final String userId;

        private Identity(String userId) {
            this.userId = userId;
        }

    }

    public static class ActiveExecutionException extends RuntimeException {
        public final Map<String, Object> activeExecution;

        private ActiveExecutionException(Map<String, Object> activeExecution) {
            this.activeExecution = activeExecution;
        }
    }

    public static class StaleExecutionException extends RuntimeException {
    }

    private static class StoredItem {
        private final long id;
        private final String json;

        private StoredItem(long id, String json) {
            this.id = id;
            this.json = json;
        }
    }

    public static class UnauthorizedException extends RuntimeException {}

    public static class NotFoundException extends RuntimeException {
        NotFoundException(String message) { super(message); }
    }

    public static class DataCorruptionException extends IllegalStateException {}
}
