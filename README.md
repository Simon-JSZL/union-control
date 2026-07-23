# union-control

Spring entrypoint for `union-web` plus the internal MySQL, execution, memory,
and authentication API used by `union-py-app`.

```bash
export JAVA_HOME=/opt/homebrew/opt/zulu8/Contents/Home
export PATH=/opt/homebrew/bin:$PATH
set -a && source .env.local && set +a
mvn spring-boot:run
```

The service binds to `127.0.0.1:8080`. Configure `MYSQL_URL`, `MYSQL_USER`,
`MYSQL_PASSWORD`, `SERVER_ADDRESS`, and `SERVER_PORT` as needed. Local MySQL
credentials belong in the git-ignored, mode-`600` `.env.local` file.

Configure the local Python application to use this Spring port for control
requests:

```text
GET_UNION_BASE_URL=http://127.0.0.1:8080
```

Local `LocalAuth` generates `CASSESSIONID=session-1` and `USERID=user-1`.
Production replaces it with the authenticated identity service. Internal
`/agent/**` requests accept only the standard semicolon-delimited Cookie header:

```text
Cookie: CASSESSIONID=...; USERID=...
```

`union-web` uses fixed `/llm/**` routes. Conversation and execution-state calls
end in control; only `/llm/chatMessage` and `/llm/chatMessageSync` forward to
`PY_APP_BASE_URL`. Set the same non-empty
`AGENT_SYNC_TOKEN` in control and py-app; control injects it for synchronous
Agent calls and never accepts it from the browser. The backend scheduler calls
py-app's behavior-risk route directly; control does not expose it.

The fixed internal Session routes used by py-app are:

- `POST /agent/conversationCreate` creates a conversation.
- `GET /agent/getConversationItems?conversationId=...` reads SDK items.
- `POST /agent/addConversationItems?conversationId=...` appends SDK items.
- `POST /agent/deleteConversationItems?conversationId=...` clears SDK items.
- `POST /agent/popConversationItem?conversationId=...` removes the last item.

Agent execution coordination adds five columns to `ai_conversation`:
`execution_status`, `execution_token`, `execution_started_at`,
`execution_heartbeat_at`, and the generated `active_execution_user_id`.
`schema.sql` uses `CREATE TABLE IF NOT EXISTS`, so it does not upgrade an
existing table. Before deploying the Python runtime, either run
`deploy/sql/20260720_add_agent_execution.sql` once or start one new
union-control instance and let its idempotent startup migration finish. Verify
all five columns and the `uk_active_execution_user` and
`idx_user_execution_status` indexes before starting Agent traffic.

The Python runtime uses one opaque value as both the Agent `trace_id` and
`execution_token`. Claim, heartbeat, cancellation detection, and finish all
carry that same value so an old worker cannot finish a newer run.

New conversations never expire unless `expiresAt` is explicitly set. Reads and
appends do not extend it. `conversation_item_json` is the sole message-content source and
is returned in sequence without summarization or field normalization.

Stop old union-control instances before deploying this column rename; old and new binaries
cannot share the message table during a rolling restart.

Conversation history uses `title` only; users can rename it through the existing
title endpoint. Fresh databases do not store login-session identifiers or
conversation summaries. Startup drops the legacy `user_session_id_hash`,
`summary_text`, and `summary_upto_seq` columns from existing databases.
Stop old union-control instances and back up `ai_conversation` before the first
upgrade; dropped legacy values cannot be recovered by rolling back the binary.

Personal long-term memory uses fixed routes:

- `GET /agent/memoryList?limit=50` lists active memories.
- `POST /agent/memorySave` upserts one memory.
- `POST /agent/memoryDelete?memoryId=...` soft-deletes one memory.

The schema reserves `scope` and `scope_id`, but this version supports only
personal scope. The server always writes `scope=user` and derives `scope_id`
from the authenticated `USERID`; payload fields such as `scope`, `scopeId`, and
`userId` are rejected. Memory has no foreign key to conversations, so it
survives conversation expiration. `sourceConversationId`, when present, must
belong to the same user.

This table is not a knowledge base. It stores explicit durable preferences,
corrections, terminology, and workflows; knowledge documents continue to be
managed separately by `union-py-app`. The unique key is
`scope + scope_id + memoryKey`, so changing Agent routing or the informational
`domain` tag does not create a second memory.

Before upgrading an existing database, stop old union-control instances or
pause memory writes, back up the table, and verify that this query returns no
rows:

```sql
SELECT scope, scope_id, memory_key, COUNT(*) AS count
FROM ai_agent_memory
GROUP BY scope, scope_id, memory_key
HAVING COUNT(*) > 1;
```

Then start the new union-control instance and verify its health before starting
the Python memory-enabled runtime. The startup migration intentionally fails
instead of choosing between duplicate rows. Old and new control binaries must
not write memory concurrently. A rollback likewise requires paused writes and
a coordinated schema/index rollback before restoring the old binary; rolling
back only the application is unsafe. The Python runtime intentionally treats an
unavailable memory API as a failed Agent run instead of silently dropping
durable user instructions.
