-- One-shot migration for an existing ai_conversation table.
-- Fresh databases already receive these definitions from schema.sql.
ALTER TABLE ai_conversation
    ADD COLUMN execution_status VARCHAR(32) NOT NULL DEFAULT 'idle' AFTER expires_at,
    ADD COLUMN execution_token VARCHAR(64) NULL AFTER execution_status,
    ADD COLUMN execution_started_at DATETIME NULL AFTER execution_token,
    ADD COLUMN execution_heartbeat_at DATETIME NULL AFTER execution_started_at,
    ADD COLUMN active_execution_user_id VARCHAR(64) GENERATED ALWAYS AS (
        CASE
            WHEN execution_status IN ('running', 'cancel_requested') THEN user_id
            ELSE NULL
        END
    ) STORED,
    ADD UNIQUE KEY uk_active_execution_user (active_execution_user_id),
    ADD KEY idx_user_execution_status (user_id, execution_status);

SELECT column_name, column_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'ai_conversation'
  AND column_name IN (
      'execution_status',
      'execution_token',
      'execution_started_at',
      'execution_heartbeat_at',
      'active_execution_user_id'
  )
ORDER BY ordinal_position;
