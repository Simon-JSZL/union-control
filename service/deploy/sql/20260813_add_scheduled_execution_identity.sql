-- Destructive rebuild: scheduled-task data in this pre-release environment is disposable.
-- Keep scheduling disabled while this script runs.
DROP TABLE IF EXISTS `agent_scheduled_task_run`;
DROP TABLE IF EXISTS `agent_scheduled_task`;

CREATE TABLE IF NOT EXISTS `agent_scheduled_task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键',
  `user_id` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '任务所属用户标识',
  `org_code` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '任务创建时的机构快照',
  `role_id` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '任务创建或编辑时的Ark系统角色快照',
  `title` VARCHAR(255) NOT NULL COMMENT '任务标题',
  `prompt` TEXT NOT NULL COMMENT '每次执行使用的自然语言任务提示',
  `schedule_type` VARCHAR(16) COLLATE utf8mb4_bin NOT NULL COMMENT '调度类型：ONCE、CRON、INTERVAL',
  `run_at` DATETIME(3) DEFAULT NULL COMMENT '单次执行时间或间隔锚点，UTC',
  `cron_expression` VARCHAR(128) COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Spring 六字段 cron 表达式',
  `interval_seconds` BIGINT UNSIGNED DEFAULT NULL COMMENT '固定执行间隔秒数',
  `timezone` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '任务 IANA 时区',
  `status` VARCHAR(16) COLLATE utf8mb4_bin NOT NULL COMMENT '任务状态：ACTIVE、PAUSED、COMPLETED',
  `next_run_at` DATETIME(3) DEFAULT NULL COMMENT '下一次计划执行时间，UTC',
  `delete_flag` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '删除标记：1-有效，0-已删除',
  `created_at` DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL COMMENT '最后更新时间，UTC',
  PRIMARY KEY (`id`),
  KEY `idx_scheduled_task_due` (`delete_flag`, `status`, `next_run_at`),
  KEY `idx_scheduled_task_user` (`user_id`, `delete_flag`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `agent_scheduled_task_run` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键',
  `task_id` BIGINT UNSIGNED NOT NULL COMMENT '所属定时任务主键',
  `scheduled_at` DATETIME(3) NOT NULL COMMENT '本次计划执行时间，UTC',
  `status` VARCHAR(16) COLLATE utf8mb4_bin NOT NULL COMMENT '运行状态：PENDING、RUNNING、SUCCEEDED、FAILED',
  `result_payload` LONGTEXT DEFAULT NULL COMMENT 'py-app 返回的标准结果 JSON',
  `result_conversation_id` VARCHAR(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '用户打开结果后创建的会话标识',
  `read_flag` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '结果读取标记：1-已打开，0-未打开',
  `error_code` VARCHAR(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '稳定错误码',
  `error_message` VARCHAR(512) DEFAULT NULL COMMENT '用户安全错误信息',
  `execution_token_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '单次运行 token 的 SHA-256 小写十六进制哈希',
  `execution_token_expires_at` DATETIME(3) DEFAULT NULL COMMENT '单次运行 token 的失效时间，UTC',
  `started_at` DATETIME(3) DEFAULT NULL COMMENT '实际开始时间，UTC',
  `finished_at` DATETIME(3) DEFAULT NULL COMMENT '实际结束时间，UTC',
  `delete_flag` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '删除标记：1-有效，0-已删除',
  `created_at` DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL COMMENT '最后更新时间，UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scheduled_run_occurrence` (`task_id`, `scheduled_at`),
  UNIQUE KEY `uk_scheduled_run_token_hash` (`execution_token_hash`),
  KEY `idx_scheduled_run_task` (`task_id`, `delete_flag`, `scheduled_at`),
  KEY `idx_scheduled_run_status` (`delete_flag`, `status`, `created_at`),
  KEY `idx_scheduled_run_unread` (`delete_flag`, `status`, `read_flag`, `finished_at`),
  KEY `idx_scheduled_run_conversation` (`result_conversation_id`),
  CONSTRAINT `fk_scheduled_run_task` FOREIGN KEY (`task_id`)
    REFERENCES `agent_scheduled_task` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
