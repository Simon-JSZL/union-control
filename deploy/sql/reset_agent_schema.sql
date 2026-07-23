-- Startup-only destructive cutover: run once before deploying the five-table schema.
SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS ai_conversation_message;
DROP TABLE IF EXISTS ai_agent_execution;
DROP TABLE IF EXISTS ai_conversation;
DROP TABLE IF EXISTS ai_memory_operation;
DROP TABLE IF EXISTS ai_memory_file;
SET FOREIGN_KEY_CHECKS = 1;
