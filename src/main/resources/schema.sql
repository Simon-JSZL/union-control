CREATE TABLE IF NOT EXISTS `ai_conversation` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键',
  `conversation_id` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '业务会话标识，对应 AG-UI threadId',
  `user_id` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '会话所属用户标识',
  `title` VARCHAR(255) DEFAULT NULL COMMENT '会话标题',
  `status` VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT '会话状态：active-活跃，archived-已归档',
  `is_pinned` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否置顶：1-是，0-否',
  `delete_flag` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '删除标记：1-有效，0-已删除',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversation_id` (`conversation_id`),
  KEY `idx_conversation_user_list` (`user_id`, `delete_flag`, `status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `ai_agent_execution` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键',
  `run_id` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT 'Agent 执行业务标识',
  `conversation_id` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属业务会话标识',
  `user_id` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '执行所属用户标识',
  `parent_execution_id` BIGINT DEFAULT NULL COMMENT '父执行数据库主键，根执行为空',
  `agent_name` VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '执行任务的 Agent 名称',
  `delegation_tool_call_id` VARCHAR(128) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '父执行发起委派的工具调用标识',
  `task` VARCHAR(1000) DEFAULT NULL COMMENT '委派给 Agent 的任务说明',
  `status` VARCHAR(32) NOT NULL COMMENT '执行状态：running-执行中，cancel_requested-已请求取消，completed-已完成，failed-执行失败，cancelled-已取消',
  `finished_at` DATETIME DEFAULT NULL COMMENT '执行结束时间',
  `error_code` VARCHAR(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '执行失败错误码',
  `delete_flag` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '删除标记：1-有效，0-已删除',
  `active_root_user_id` VARCHAR(64) COLLATE utf8mb4_bin GENERATED ALWAYS AS (
    CASE
      WHEN `delete_flag` = 1
       AND `parent_execution_id` IS NULL
       AND `status` IN ('running', 'cancel_requested')
      THEN `user_id`
      ELSE NULL
    END
  ) STORED COMMENT '活跃根执行的用户标识，用于限制每个用户只能有一个活跃根执行',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_execution_run` (`run_id`),
  UNIQUE KEY `uk_active_root_user` (`active_root_user_id`),
  UNIQUE KEY `uk_parent_delegation_call` (`parent_execution_id`, `delegation_tool_call_id`),
  KEY `idx_execution_conversation` (`conversation_id`, `delete_flag`, `created_at`),
  KEY `idx_execution_parent` (`parent_execution_id`, `delete_flag`, `created_at`),
  KEY `idx_execution_user_status` (`user_id`, `delete_flag`, `status`),
  CONSTRAINT `fk_execution_conversation` FOREIGN KEY (`conversation_id`)
    REFERENCES `ai_conversation` (`conversation_id`),
  CONSTRAINT `fk_execution_parent` FOREIGN KEY (`parent_execution_id`)
    REFERENCES `ai_agent_execution` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `ai_conversation_message` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键',
  `message_id` VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '消息业务标识',
  `conversation_id` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属业务会话标识',
  `user_id` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '消息所属用户标识',
  `agent_execution_id` BIGINT NOT NULL COMMENT '消息所属执行数据库主键',
  `role` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '消息角色：user-用户，assistant-助手，tool-工具结果，system-系统，developer-开发者，reasoning-推理',
  `sequence_no` BIGINT NOT NULL COMMENT '会话范围内的消息顺序号',
  `payload` JSON NOT NULL COMMENT '消息角色特有字段 JSON',
  `delete_flag` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '删除标记：1-有效，0-已删除',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversation_message` (`conversation_id`, `message_id`),
  UNIQUE KEY `uk_conversation_sequence` (`conversation_id`, `sequence_no`),
  KEY `idx_message_history` (`conversation_id`, `user_id`, `delete_flag`, `sequence_no`),
  KEY `idx_message_execution` (`agent_execution_id`, `delete_flag`, `sequence_no`),
  CONSTRAINT `fk_message_conversation` FOREIGN KEY (`conversation_id`)
    REFERENCES `ai_conversation` (`conversation_id`),
  CONSTRAINT `fk_message_execution` FOREIGN KEY (`agent_execution_id`)
    REFERENCES `ai_agent_execution` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `ai_memory_file` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键',
  `user_id` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '记忆文件所属用户标识',
  `path` VARCHAR(512) COLLATE utf8mb4_bin NOT NULL COMMENT '用户命名空间内的文件路径',
  `content` MEDIUMTEXT NOT NULL COMMENT '记忆文件内容',
  `version` BIGINT NOT NULL COMMENT '文件内容版本号',
  `last_operation_id` VARCHAR(128) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '最后一次修改文件的幂等操作标识',
  `delete_flag` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '删除标记：1-有效，0-已删除',
  `active_path` VARCHAR(512) COLLATE utf8mb4_bin GENERATED ALWAYS AS (
    CASE WHEN `delete_flag` = 1 THEN `path` ELSE NULL END
  ) STORED COMMENT '有效文件路径，用于实现软删除后的路径唯一约束',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_memory_file_active_path` (`user_id`, `active_path`),
  KEY `idx_memory_file_prefix` (`user_id`, `delete_flag`, `path`(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `ai_memory_operation` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键',
  `user_id` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '操作所属用户标识',
  `operation_id` VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '客户端幂等操作标识',
  `fingerprint` VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '操作请求内容指纹',
  `result_version` BIGINT DEFAULT NULL COMMENT '操作结果版本号，删除操作为空',
  `existed` TINYINT(1) NOT NULL COMMENT '操作前文件是否存在：1-存在，0-不存在',
  `delete_flag` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '删除标记：1-有效，0-已删除',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_memory_operation` (`user_id`, `operation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `sensitive_data_demo` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键',
  `user_id` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '数据所属用户标识',
  `encrypted_content` TEXT NOT NULL COMMENT '数据库 AES 拦截器写入的密文',
  `delete_flag` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '删除标记：1-有效，0-已删除',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_sensitive_demo_list` (`user_id`, `delete_flag`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `agent_scheduled_task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键',
  `user_id` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '任务所属用户标识',
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
  `started_at` DATETIME(3) DEFAULT NULL COMMENT '实际开始时间，UTC',
  `finished_at` DATETIME(3) DEFAULT NULL COMMENT '实际结束时间，UTC',
  `delete_flag` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '删除标记：1-有效，0-已删除',
  `created_at` DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL COMMENT '最后更新时间，UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scheduled_run_occurrence` (`task_id`, `scheduled_at`),
  KEY `idx_scheduled_run_task` (`task_id`, `delete_flag`, `scheduled_at`),
  KEY `idx_scheduled_run_status` (`delete_flag`, `status`, `created_at`),
  KEY `idx_scheduled_run_unread` (`delete_flag`, `status`, `read_flag`, `finished_at`),
  KEY `idx_scheduled_run_conversation` (`result_conversation_id`),
  CONSTRAINT `fk_scheduled_run_task` FOREIGN KEY (`task_id`)
    REFERENCES `agent_scheduled_task` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
