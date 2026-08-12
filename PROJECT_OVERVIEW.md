# Project Overview

## Purpose

`union-control` is the browser-facing control plane for the PydanticAI service.
It owns authenticated conversation/run state, standard AG-UI message
persistence, the official Memory store protocol adapter, and transparent AG-UI
SSE proxying.

## Runtime

- Java 8
- Spring Boot 1.5
- MySQL 8
- Browser APIs remain under `/llm/**`
- Internal py-app APIs remain under `/agent/**` and validate the CAS session
  obtained by control

Only GET and POST endpoints are used in production.

Natural-language scheduled-task browser APIs also live under `/llm/**`.
They create `ONCE`, six-field Spring `CRON`, or fixed `INTERVAL` tasks and
expose task/run lists, unread results, and an explicit open-result action.

## Protocol

- `POST /llm/chatMessage` accepts the standard AG-UI `RunAgentInput`, claims
  the user’s single active run, and transparently proxies AG-UI SSE.
- `GET /llm/conversationDetails` returns conversation metadata and a
  `messages` array of standard AG-UI messages.
- `POST /llm/executionCancel` requires `conversationId` and `runId`, records
  the cancellation request, and sends an owner-scoped cancel request to py-app.
- `POST /llm/chatMessageSync` keeps the product `{content}` response.

There is no execution-current/recovery endpoint, legacy event translation,
SDK item API, heartbeat event, or dual-write path.

## Persistence

`ai_conversation` owns only conversation metadata and status.

`ai_agent_execution` owns Coordinator root executions and their subagent
children. A generated-column unique key enforces one active root execution per
user while allowing multiple children under that root.

`ai_conversation_message` stores:

- message ID
- role and role-specific JSON payload
- an `agent_execution_id` foreign key
- a stable per-conversation sequence
- `delete_flag`

Browser history reconstructs each child execution as a standard AG-UI
`ActivityMessage`; model history contains root execution messages only.

Browser SSE disconnects cancel the exact claimed root and its children. Root
and child transitions are one-way and transactional: a committed
`cancel_requested` state can only become `cancelled`. Startup and scheduled
cleanup terminate stale `running` and `cancel_requested` trees, while py-app's
matching hard deadline stops the underlying model and tool work.

`ai_memory_file` and `ai_memory_operation` implement PydanticAI Harness
`SearchableMemoryStore` semantics: bounded read/list/search, CAS versioning,
operation-ID idempotency, and operation fingerprint conflicts.

The isolated `sensitive_data_demo` flow mirrors production field encryption at
the MyBatis database boundary. The normal interceptor encrypts writes and
decrypts reads; a post-decrypt hook replaces plaintext with a masked value and
an owner-bound Redis reveal token. Plaintext reveal is a separate authenticated
API path and never re-enters the masking hook.

`agent_scheduled_task` and `agent_scheduled_task_run` persist task definitions
and individual outcomes. The task table owns the natural-language prompt,
schedule definition, state, timezone, and next due time. The run table owns one
scheduled occurrence, its execution state, stored result or safe error, unread
state, and optional materialized conversation ID. No additional token or
run-item table is involved.

The scheduled-task flow is:

1. A MySQL-backed `@Scheduled` scanner locks due active tasks with
   `FOR UPDATE SKIP LOCKED`, inserts a `PENDING` run, and advances the task's
   next due time. The `(task_id, scheduled_at)` unique key prevents duplicate
   occurrences across control instances. Claiming an `ONCE` task atomically
   marks its definition `COMPLETED` while the run itself remains the source of
   truth for `PENDING` / `RUNNING` / terminal progress, so pause/start cannot
   enqueue the consumed occurrence again.
2. Pending runs are moved to `RUNNING` and submitted to a bounded worker pool.
   Control calls py-app with only the run ID and the scheduled-task bearer
   credential; py-app obtains the trusted task context and uses the owner-bound
   read-only tool routes through control.
3. Every terminal run is unread until opened. Successful py-app content is
   stored as bounded JSON; failed runs retain only a fixed safe error. Neither
   creates a conversation in the background.
4. `scheduledTaskRunOpen` verifies the browser owner and locks the run. It
   idempotently creates one normal AG-UI conversation with exactly two trusted
   messages: the task prompt as the user message and the stored final content
   as the assistant message. It marks the result read and returns the existing
   conversation on repeated opens.

Only the successful result's `content` and `agentName` fields are persisted.
Provider messages and unknown response fields are discarded at the control
boundary, and failed callbacks store a fixed user-safe message rather than an
upstream error body. Opening either terminal state creates the same standard
two-message conversation, so users can follow up on a result or a failure.

Every logical delete sets `delete_flag=0`; active rows use
`delete_flag=1`. The former message and personal-memory schemas are not
migrated or retained because the feature was not released.

## Security

- Scheduled-task browser routes authenticate the caller's same-origin
  `CASSESSIONID`; the controller never substitutes a synthetic browser identity.
  Local development validates the fixed session through the `LocalAuth` mock.
  Production must replace that mock with the real authentication-service
  integration before enabling this feature.
- Normal Agent, tool, history, completion, cancellation, sync, and Memory calls
  forward and validate that `CASSESSIONID`.
- Fixed business scenarios share a generic Authorization-token mechanism.
  Behavior risk currently uses `BEHAVIOR_RISK_TOKEN`.
- Scheduled execution context and read-only scheduled tools use the independent
  `SCHEDULED_TASK_TOKEN`; the scheduler is disabled by default and startup
  fails if it is enabled without that token.
- Conversation completion and cancellation validate user/conversation/run
  ownership.
- Memory paths must begin with `<authenticated-user>/personal/`.
- Agent, Skill, memory namespace, model, provider, and frontend tools are not
  accepted from browser configuration.

## Scheduled-task configuration

- `SCHEDULED_TASK_TOKEN` is the shared secret used only by control and py-app
  scheduled routes. Both services must use the same non-empty value; do not
  expose it to the browser.
- When `PY_APP_BASE_URL` crosses a trusted host boundary, protect this bearer
  credential with HTTPS plus mTLS or an equivalent authenticated service mesh;
  plain HTTP is suitable only for loopback development.
- `SCHEDULED_TASK_ENABLED` controls claiming and execution and defaults to
  `false`. Control fails startup when it is `true` and the token is empty.
- Scanner tuning: `SCHEDULED_TASK_INITIAL_DELAY_MS`,
  `SCHEDULED_TASK_SCAN_INTERVAL_MS`, and `SCHEDULED_TASK_SCAN_BATCH_SIZE`.
- Worker tuning: `SCHEDULED_TASK_WORKER_THREADS`,
  `SCHEDULED_TASK_WORKER_QUEUE`, and `SCHEDULED_TASK_MAX_RUN_SECONDS`.
- Contract limits: `SCHEDULED_TASK_MIN_INTERVAL_SECONDS`,
  `SCHEDULED_TASK_CONNECT_TIMEOUT_MS`, and
  `SCHEDULED_TASK_READ_TIMEOUT_MS`.

The defaults leave 30 seconds beyond py-app's 900-second runtime deadline:
`SCHEDULED_TASK_MAX_RUN_SECONDS=930` and
`SCHEDULED_TASK_READ_TIMEOUT_MS=930000`. Preserve that transport margin when
overriding them. A shorter proxy timeout can mark a still-running Agent as
failed while py-app continues until its own deadline.

Defaults are defined in `src/main/resources/application.yml`. Keep the
scheduler disabled while deploying or rolling back incompatible control and
py-app versions.

## Scheduled-task migration and rollback

Roll out in this order:

1. Apply `deploy/sql/20260810_add_agent_scheduled_tasks.sql`; it creates
   `agent_scheduled_task` before its child `agent_scheduled_task_run`.
2. Configure the same scheduled-task credential in control and py-app, deploy
   both services, and leave control scheduling disabled.
3. Deploy the web UI and verify draft, create, update, list, pause/start, and result
   APIs while no background run can be claimed.
4. Enable scheduling on control only after the cross-service contract and
   database migration are verified.

Roll back in this order:

1. Disable scheduling on every control instance and restart or drain them so
   no new run is claimed; allow or explicitly terminate in-flight work.
2. Roll back the web UI, then control, then py-app. The additive tables can
   remain in place and should be retained by default so task and run history is
   recoverable.
3. Only after all deployed versions no longer reference the feature and data
   retention has been approved, drop `agent_scheduled_task_run` first and
   `agent_scheduled_task` second because of the foreign key. Table removal is
   destructive and is not part of a routine service rollback.

## Validation

```bash
JAVA_HOME=/path/to/java8 mvn clean test
```
