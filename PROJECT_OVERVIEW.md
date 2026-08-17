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
  conversation, execution, and message records must be created through the
  shared `ConversationService` and `AgentExecutionService` paths rather than
  direct inserts from a domain mapper.

### Preserve one identity and tool path

- Interactive execution forwards the validated CAS cookie unchanged. Scheduled
  execution uses a separate single-occurrence, short-lived, sessionless token;
  Control stores only its SHA-256 hash and restores the task's trusted
  `userId + orgCode + roleId` snapshot through Shiro on every request.
- Scheduled traffic carries only `Authorization: Scheduled <token>`. It enters
  py through `/agent/v1/runs/scheduled`, introspects via
  `/agent/scheduledExecutionIdentity`, and returns the same token to existing
  `/agent/*` tools. It never derives a Cookie, sends identity fields, or creates
  Tool replicas. This is an authentication adapter around the same execution
  kernel, not a second Agent execution plane.
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

Java sources follow the production project's responsibility-based packages:
MyBatis interfaces in `mapper`, application services in `service`, timer
entrypoints in `scheduled`, and additive Shiro components in `security`.
Scheduled-task code must not introduce a parallel domain package containing its
own mapper, service, Web facade, production-authentication wrapper, or local mock.

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
   Control loads the prompt, owner, role, timezone, and effective occurrence
   time, atomically issues a token, and calls py's scheduled authentication
   adapter with `{}`. The Scheduled Realm restores the trusted
   `userId + orgCode + roleId` as the existing production `ShiroUser` shape.
   The existing production Realm remains the only role-resource and
   authorization owner; no permission-provider wrapper or permission snapshot
   is added by this feature. Existing `@RequiresPermissions` annotations remain
   the only mapping from a tool API to its page-level permission; py does not
   perform a second permission decision.
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
  Apache Shiro enforces `@RequiresPermissions("agent:execute")` on every
  CAS-protected API. Local development maps the fixed session to that permission
  through the `LocalCasRealm` development adapter.
  Production must replace that adapter with the real authentication-service
  integration before enabling this feature.
- Normal Agent, tool, history, completion, cancellation, sync, and Memory calls
  forward and validate that `CASSESSIONID`.
- Scheduled execution has no browser session and must not derive or fabricate a
  CAS Cookie from the task owner. Its Shiro Subject is reconstructed from the
  active RUNNING occurrence token without Session or Realm caches.
- Conversation completion and cancellation validate user/conversation/run
  ownership.
- Memory paths must begin with `<authenticated-user>/personal/`.
- Agent, Skill, memory namespace, model, provider, and frontend tools are not
  accepted from browser configuration.

## Scheduled-task configuration

- `SCHEDULED_TASK_ENABLED` controls claiming and execution and defaults to
  `false` until the existing production Realm accepts the restored scheduled
  `ShiroUser`, the database migration and py scheduled adapter are deployed,
  and cross-service security checks are complete.
- Worker tuning: `SCHEDULED_TASK_WORKER_THREADS` and
  `SCHEDULED_TASK_MAX_RUN_SECONDS`.

The scanner runs every five seconds, claims at most 20 tasks per pass, queues
at most 32 worker submissions, and rejects schedules more frequent than once
per minute. These implementation limits are fixed in code. Configurable defaults
are defined in `service/src/main/resources/application.yml`. Keep the
scheduler disabled while deploying or rolling back incompatible control and
py-app versions.

## Scheduled-task migration and rollback

Roll out in this order:

1. Leave scheduling disabled and apply
   `service/deploy/sql/20260813_add_scheduled_execution_identity.sql`. It drops
   `agent_scheduled_task_run` first and `agent_scheduled_task` second, then
   recreates both from the canonical schema. All pre-release task and run data
   is intentionally discarded.
2. Register the Control Scheduled Realm beside the existing production Realm,
   verify the existing Realm authorizes its restored `ShiroUser`, and deploy the
   py scheduled adapter while scheduling remains disabled.
3. Deploy the web UI and verify draft, create, update, list, pause/start, and result
   APIs while no background run can be claimed.
4. Enable scheduling on control only after the cross-service contract and
   database migration are verified.

Roll back in this order:

1. Disable scheduling on every control instance and restart or drain them so
   no new run is claimed; allow or explicitly terminate in-flight work.
2. Roll back the web UI, then control, then py-app. The scheduled-task tables
   may remain in place.
3. If the tables must be removed, drop `agent_scheduled_task_run` first and
   `agent_scheduled_task` second because of the foreign key.

## Validation

```bash
JAVA_HOME=/path/to/java8 mvn clean test
```
