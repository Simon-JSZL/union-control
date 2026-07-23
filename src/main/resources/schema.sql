CREATE TABLE IF NOT EXISTS ai_conversation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    title VARCHAR(255),
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    expires_at DATETIME NULL,
    execution_status VARCHAR(32) NOT NULL DEFAULT 'idle',
    execution_token VARCHAR(64) NULL,
    execution_started_at DATETIME NULL,
    execution_heartbeat_at DATETIME NULL,
    active_execution_user_id VARCHAR(64) GENERATED ALWAYS AS (
        CASE WHEN execution_status IN ('running','cancel_requested') THEN user_id ELSE NULL END
    ) STORED,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_conversation_user (conversation_id, user_id),
    UNIQUE KEY uk_active_execution_user (active_execution_user_id),
    KEY idx_user_execution_status (user_id, execution_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_conversation_message (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    seq BIGINT NOT NULL,
    conversation_item_json JSON NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_conversation_message_seq (conversation_id, user_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_agent_memory (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    scope VARCHAR(16) NOT NULL DEFAULT 'user',
    scope_id VARCHAR(64) NOT NULL,
    domain VARCHAR(32) NOT NULL,
    memory_type VARCHAR(32) NOT NULL,
    memory_key VARCHAR(128) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    source_conversation_id VARCHAR(64) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_memory_scope_key (scope, scope_id, memory_key),
    KEY idx_agent_memory_recall (scope, scope_id, status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
