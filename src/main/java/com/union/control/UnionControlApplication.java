package com.union.control;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@SpringBootApplication
public class UnionControlApplication {
    public static void main(String[] args) {
        SpringApplication.run(UnionControlApplication.class, args);
    }

    @Bean
    public ApplicationRunner migrateConversationSchema(JdbcTemplate jdbc) {
        return args -> {
            ensureConversationExpiration(jdbc);
            ensureAgentExecutionState(jdbc);
            dropLegacyConversationMetadata(jdbc);
            migrateConversationMessages(jdbc);
            migrateAgentMemoryKeyIndex(jdbc);
        };
    }

    static void ensureConversationExpiration(JdbcTemplate jdbc) {
        // ponytail: startup migrations are deployed serially; add a migration tool if schema evolution grows.
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns " +
                        "WHERE table_schema=DATABASE() AND table_name='ai_conversation' AND column_name='expires_at'",
                Integer.class);
        if (count == null || count == 0) {
            jdbc.execute("ALTER TABLE ai_conversation ADD COLUMN expires_at DATETIME NULL AFTER status");
        }
    }

    static void ensureAgentExecutionState(JdbcTemplate jdbc) {
        List<String> columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns " +
                        "WHERE table_schema=DATABASE() AND table_name='ai_conversation'",
                String.class);
        List<String> changes = new ArrayList<>();
        if (!columns.contains("execution_status")) {
            changes.add("ADD COLUMN execution_status VARCHAR(32) NOT NULL DEFAULT 'idle' AFTER expires_at");
        }
        if (!columns.contains("execution_token")) {
            changes.add("ADD COLUMN execution_token VARCHAR(64) NULL AFTER execution_status");
        }
        if (!columns.contains("execution_started_at")) {
            changes.add("ADD COLUMN execution_started_at DATETIME NULL AFTER execution_token");
        }
        if (!columns.contains("execution_heartbeat_at")) {
            changes.add("ADD COLUMN execution_heartbeat_at DATETIME NULL AFTER execution_started_at");
        }
        if (!columns.contains("active_execution_user_id")) {
            changes.add("ADD COLUMN active_execution_user_id VARCHAR(64) GENERATED ALWAYS AS " +
                    "(CASE WHEN execution_status IN ('running','cancel_requested') THEN user_id ELSE NULL END) STORED");
        }
        if (!changes.isEmpty()) {
            jdbc.execute("ALTER TABLE ai_conversation " + String.join(",", changes));
        }

        List<String> indexes = jdbc.queryForList("SELECT DISTINCT index_name FROM information_schema.statistics " +
                        "WHERE table_schema=DATABASE() AND table_name='ai_conversation'",
                String.class);
        if (!indexes.contains("uk_active_execution_user")) {
            jdbc.execute("ALTER TABLE ai_conversation " +
                    "ADD UNIQUE KEY uk_active_execution_user (active_execution_user_id)");
        }
        if (!indexes.contains("idx_user_execution_status")) {
            jdbc.execute("ALTER TABLE ai_conversation " +
                    "ADD KEY idx_user_execution_status (user_id,execution_status)");
        }
    }

    static void dropLegacyConversationMetadata(JdbcTemplate jdbc) {
        List<String> columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns " +
                        "WHERE table_schema=DATABASE() AND table_name='ai_conversation'",
                String.class);
        List<String> changes = new ArrayList<>();
        for (String column : Arrays.asList(
                "user_session_id_hash", "summary_text", "summary_upto_seq", "summary_uptoz_seq")) {
            if (columns.contains(column)) changes.add("DROP COLUMN " + column);
        }
        if (!changes.isEmpty()) {
            jdbc.execute("ALTER TABLE ai_conversation " + String.join(",", changes));
        }
    }

    static void migrateConversationMessages(JdbcTemplate jdbc) {
        List<String> columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns " +
                        "WHERE table_schema=DATABASE() AND table_name='ai_conversation_message' ORDER BY ordinal_position",
                String.class);
        List<String> indexes = jdbc.queryForList("SELECT DISTINCT index_name FROM information_schema.statistics " +
                        "WHERE table_schema=DATABASE() AND table_name='ai_conversation_message'",
                String.class);
        List<String> legacyColumns = Arrays.asList("role", "item_type", "agent_name", "content",
                "reasoning_content", "tool_name", "call_id", "tool_arguments", "tool_result", "finish_reason",
                "error_msg");
        List<String> knownColumns = new ArrayList<>(Arrays.asList(
                "id", "conversation_id", "user_id", "seq", "conversation_item_json", "created_at", "sdk_item_json"));
        knownColumns.addAll(legacyColumns);
        if (!columns.containsAll(Arrays.asList("id", "conversation_id", "user_id", "seq", "created_at")) ||
                !knownColumns.containsAll(columns)) {
            throw new IllegalStateException("消息表存在缺失或未识别字段");
        }
        boolean hasOldJson = columns.contains("sdk_item_json");
        boolean hasCurrentJson = columns.contains("conversation_item_json");
        if (hasOldJson && hasCurrentJson) {
            throw new IllegalStateException("消息表同时存在新旧 JSON 字段");
        }
        if (hasOldJson) {
            Integer missing = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ai_conversation_message WHERE sdk_item_json IS NULL", Integer.class);
            if (missing != null && missing > 0) {
                throw new IllegalStateException("消息表存在缺失 SDK item JSON 的历史行");
            }
        } else if (!hasCurrentJson) {
            throw new IllegalStateException("消息表缺少 conversation_item_json 字段");
        }

        List<String> changes = new ArrayList<>();
        if (hasOldJson) {
            changes.add("CHANGE COLUMN sdk_item_json conversation_item_json JSON NOT NULL");
        }
        for (String column : legacyColumns) {
            if (columns.contains(column)) changes.add("DROP COLUMN " + column);
        }
        if (indexes.contains("idx_conversation_message")) {
            changes.add("DROP INDEX idx_conversation_message");
        }
        if (!changes.isEmpty()) {
            jdbc.execute("ALTER TABLE ai_conversation_message " + String.join(",", changes));
        }
    }

    static void migrateAgentMemoryKeyIndex(JdbcTemplate jdbc) {
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.statistics " +
                        "WHERE table_schema=DATABASE() AND table_name='ai_agent_memory' " +
                        "AND index_name='uk_agent_memory_scope_key' ORDER BY seq_in_index",
                String.class);
        List<String> current = Arrays.asList("scope", "scope_id", "memory_key");
        if (columns.equals(current)) return;
        List<String> legacy = Arrays.asList("scope", "scope_id", "domain", "memory_key");
        if (!columns.isEmpty() && !columns.equals(legacy)) {
            throw new IllegalStateException("个人记忆唯一索引结构无法识别");
        }
        jdbc.execute("ALTER TABLE ai_agent_memory " +
                (columns.isEmpty() ? "" : "DROP INDEX uk_agent_memory_scope_key,") +
                "ADD UNIQUE KEY uk_agent_memory_scope_key (scope,scope_id,memory_key)");
    }
}
