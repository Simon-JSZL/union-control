# Project Overview

`union-control` is the single browser entrypoint and the internal persistence
service used by `union-py-app`. `LlmController` exposes fixed `/llm/**` routes:
database-only requests end in control/MySQL, while Agent runs stream or proxy to
py-app. `AgentController` exposes fixed `/agent/**` routes only for py-app
Session, execution, and memory calls. Both controllers delegate business work
and SQL to `ControlService`; `LlmController` delegates Python forwarding to
`AgentProxyService`. Controllers never call each other.

## Stack and entrypoint

- Java 8, Spring Boot 1.5.22, Spring Framework 4.3.25
- Spring JDBC and MySQL Connector/J 8.0.33 against MySQL 8.4
- `com.union.control.UnionControlApplication`

Conversation endpoints require `USERID`, as do `/agent/memoryList`,
`/agent/memorySave`, and `/agent/memoryDelete`. Memory ownership is
derived only from that authenticated cookie; v1 always uses personal
`scope=user`, and `memoryKey` is unique for that user regardless of the
informational `domain` tag.

## Agent execution coordination

`ai_conversation` is also the execution control plane; no Redis or separate
execution table is used. `execution_status` is `idle`, `running`, or
`cancel_requested`. A generated `active_execution_user_id` column and unique
index enforce at most one active conversation per user across all workers.
Conversation history displays and edits `title`; no conversation summary is
stored or generated.

`run_error` items share the conversation message table so browser history can
restore failed turns in order. `/llm/conversationDetails` includes them, while
the SDK-facing `/agent/getConversationItems` and `/agent/popConversationItem`
exclude them so errors are never replayed into Agent context.

The fixed Cookie-authenticated internal endpoints are `/agent/executionClaim`,
`/agent/executionHeartbeat`, and `/agent/executionFinish`. Claim checks
before creating a conversation and returns HTTP 409 with
`errorCode=agent_run_active` on conflict. Heartbeat and finish compare the
opaque execution token, so a stale worker cannot clear a newer execution.
After the user requests cancellation, a missing worker heartbeat for 30 seconds
releases the record, covering a crashed owning worker.

`schema.sql` creates these fields only for a fresh table; `CREATE TABLE IF NOT
EXISTS` does not alter an existing table. Existing databases must run
`deploy/sql/20260720_add_agent_execution.sql` once or start one new
union-control instance to execute the idempotent startup migration. Verify the
five execution columns plus `uk_active_execution_user` and
`idx_user_execution_status` before deploying union-py-app. The Python runtime
uses the same opaque value for `trace_id` and `execution_token`.

To roll it back, first stop Agent traffic and set all executions to `idle`;
then drop `uk_active_execution_user` followed by
`idx_user_execution_status`, `active_execution_user_id`,
`execution_heartbeat_at`, `execution_started_at`, `execution_token`, and
`execution_status`.

## Public and internal routes

- Web calls `/llm/conversationList`, `/llm/conversationDetails`,
  `/llm/conversationTitle`, `/llm/conversationRestore`,
  `/llm/conversationExpire`, `/llm/executionCurrent`, and
  `/llm/executionCancel`; these never enter py-app.
- `/llm/chatMessage` and `/llm/chatMessageSync` forward to py-app at
  `PY_APP_BASE_URL`; control injects its configured `AGENT_SYNC_TOKEN` for the
  synchronous route. The backend scheduler calls py-app's behavior-risk route
  directly.
- Local control generates `CASSESSIONID=session-1` and `USERID=user-1` for its
  own routes. `/agent/authCurrent` belongs to the existing authentication
  controller and is not implemented here.
- All business routes are fixed; identifiers are query or body parameters.
- Production transport supports only GET and POST; controllers must not expose
  PUT, PATCH, or DELETE mappings.

## Validation

```bash
JAVA_HOME=/opt/homebrew/opt/zulu8/Contents/Home PATH=/opt/homebrew/bin:$PATH mvn clean test
```
