ALTER TABLE `ai_agent_execution`
  DROP INDEX `uk_active_root_user`,
  DROP COLUMN `active_root_user_id`,
  MODIFY COLUMN `agent_name` VARCHAR(128) COLLATE utf8mb4_bin NULL
    COMMENT 'py-app 最终提交的 Agent 名称';
