# Project Overview

## Mandatory Architecture Contract

This section is the highest-level implementation constraint for every future
feature iteration. New requirements must comply with it before lower sections,
existing scenario code, or tests are considered. Existing code that conflicts
with this contract is technical debt to remove, not a precedent to copy.

### One browser-to-Agent gateway

- Domain controllers own browser and MySQL interactions for their domain. They
  must not proxy requests to a model service or expose callbacks, context APIs,
  or tool replicas for py-app. All browser-to-Agent traffic belongs to
  `LlmController` and the shared `AgentProxyService` execution methods.
- A new business scenario may add its domain specialist Agent and domain tools
  in py-app, plus its own CRUD/persistence services in control when required. It
  must not add a scenario-specific LLM controller route, proxy method, bearer
  credential, long-timeout HTTP client, tool forwarding endpoint, completion
  callback, or alternate conversation persistence path.
- Scheduling, batching, and other orchestration are invocation mechanisms, not
  Agent domains. They may decide when to invoke work and persist their own job
  state, but they must call the same normal Agent execution plane as an
  interactive caller.

### Reuse stream or non-stream runs

- Use `/llm/chatMessage` when the caller needs AG-UI streaming and
  `/llm/chatMessageSync` when it needs a final synchronous result. Both paths
  must preserve the same CAS-authenticated identity and call the corresponding
  shared py-app run endpoint.
- Background work such as a due scheduled occurrence must invoke the shared
  non-stream application service behind `chatMessageSync`; it must not create a
  second browser route just to forward to the model, nor call a scenario-specific
  py-app route.
- Domain job state and final result payload may remain in domain tables. Normal
  conversation, execution, and message records must be created through shared
  `ControlService` ownership rather than direct inserts from a domain mapper.

### Preserve one identity and tool path

- The validated caller CAS cookie is the single user authorization context
  forwarded to py-app and returned on all py-app-to-control tool calls. A
  scenario service token may authenticate a truly user-independent fixed
  system integration, but must never stand in for a user session or authorize
  user-owned tools and data.
- Background execution therefore requires an authentication-service-approved
  delegated session or session exchange for the task owner. Persisting a raw
  long-lived cookie, synthesizing a cookie from `userId`, or creating bearer-
  authenticated copies of every tool endpoint is forbidden. Until the delegated
  session mechanism exists, production background execution is blocked rather
  than permitted to bypass the normal identity chain.
- Each business tool has one control endpoint and one authorization
  implementation. Scenario-prefixed clones are forbidden.

### Layer and review gate

Transaction/control-plane packages contain reusable orchestration and runtime
mechanisms only; scenario business code belongs in its domain package. Before
implementation, every feature proposal must identify: domain persistence
ownership, the specialist and tools added or reused, stream versus non-stream
mode, the shared LLM entrypoint, and the full cookie identity path. Introducing
a second path in any category requires an explicit architecture decision
recorded in this section before code is written. Tests must enforce reuse of the
shared route and shared identity/tool path, and reviewers must reject violations
even when isolated feature tests pass.

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
Draft generation is an ordinary browser call to `/llm/chatMessageSync`; there
is no scheduled-task-specific model endpoint.

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
   Control loads the prompt, owner, timezone, and effective occurrence time,
   obtains a delegated CAS session for that owner, builds the existing sync
   request contract, and invokes the same non-stream application service used by
   `/llm/chatMessageSync`. py-app uses the normal cookie-authenticated `/agent/*`
   tool routes.
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
- Scheduled execution uses a delegated CAS session from the authentication
  boundary. The scheduler is disabled by default and startup fails if it is
  enabled without a configured delegated-session provider. The explicit local
  adapter supports only the fixed `LocalAuth` mock user and is disabled by default.
- Conversation completion and cancellation validate user/conversation/run
  ownership.
- Memory paths must begin with `<authenticated-user>/personal/`.
- Agent, Skill, memory namespace, model, provider, and frontend tools are not
  accepted from browser configuration.

## Scheduled-task configuration

- `SCHEDULED_TASK_ENABLED` controls claiming and execution and defaults to
  `false`. Control fails startup when it is `true` and no delegated-session
  provider is configured.
- `SCHEDULED_TASK_LOCAL_DELEGATED_SESSION_ENABLED` enables only the clearly
  marked `LocalAuth` development adapter. It must remain disabled in production.
- Scanner tuning: `SCHEDULED_TASK_INITIAL_DELAY_MS`,
  `SCHEDULED_TASK_SCAN_INTERVAL_MS`, and `SCHEDULED_TASK_SCAN_BATCH_SIZE`.
- Worker tuning: `SCHEDULED_TASK_WORKER_THREADS`,
  `SCHEDULED_TASK_WORKER_QUEUE`, and `SCHEDULED_TASK_MAX_RUN_SECONDS`.
- Contract limit: `SCHEDULED_TASK_MIN_INTERVAL_SECONDS`.

Defaults are defined in `src/main/resources/application.yml`. Keep the
scheduler disabled while deploying or rolling back incompatible control and
py-app versions.

## Scheduled-task migration and rollback

Roll out in this order:

1. Apply `deploy/sql/20260810_add_agent_scheduled_tasks.sql`; it creates
   `agent_scheduled_task` before its child `agent_scheduled_task_run`.
2. Deploy the authentication-service delegated-session integration, control,
   and py-app, and leave control scheduling disabled.
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
