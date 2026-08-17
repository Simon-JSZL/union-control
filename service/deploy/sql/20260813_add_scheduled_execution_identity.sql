-- Scheduler must remain disabled for this migration.
-- Existing org_code values must be backfilled from an approved authoritative source;
-- no default organization is safe for an identity snapshot.
ALTER TABLE agent_scheduled_task
  ADD COLUMN org_code VARCHAR(64) COLLATE utf8mb4_bin DEFAULT NULL
    COMMENT '任务创建时的机构快照' AFTER user_id;

ALTER TABLE agent_scheduled_task
  ADD COLUMN role_id VARCHAR(64) COLLATE utf8mb4_bin DEFAULT NULL
    COMMENT '任务创建或编辑时的Ark系统角色快照' AFTER org_code;

-- Stop here, backfill authoritatively, and verify:
-- SELECT COUNT(*) FROM agent_scheduled_task
-- WHERE org_code IS NULL OR org_code='' OR role_id IS NULL OR role_id='';
-- Continue only when the result is zero.
ALTER TABLE agent_scheduled_task
  MODIFY COLUMN org_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL
    COMMENT '任务创建时的机构快照',
  MODIFY COLUMN role_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL
    COMMENT '任务创建或编辑时的Ark系统角色快照';

ALTER TABLE agent_scheduled_task_run
  ADD COLUMN execution_token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL
    COMMENT '单次运行 token 的 SHA-256 小写十六进制哈希',
  ADD COLUMN execution_token_expires_at DATETIME(3) DEFAULT NULL
    COMMENT '单次运行 token 的失效时间，UTC',
  ADD UNIQUE KEY uk_scheduled_run_token_hash (execution_token_hash);
