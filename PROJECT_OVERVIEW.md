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

Every logical delete sets `delete_flag=0`; active rows use
`delete_flag=1`. The former message and personal-memory schemas are not
migrated or retained because the feature was not released.

## Security

- Browser requests do not supply identity credentials. Control obtains the
  authenticated CAS session from the authentication service; local development
  uses the `LocalAuth` mock.
- Normal Agent, tool, history, completion, cancellation, sync, and Memory calls
  forward and validate that `CASSESSIONID`.
- Fixed business scenarios share a generic Authorization-token mechanism.
  Behavior risk currently uses `BEHAVIOR_RISK_TOKEN`.
- Conversation completion and cancellation validate user/conversation/run
  ownership.
- Memory paths must begin with `<authenticated-user>/personal/`.
- Agent, Skill, memory namespace, model, provider, and frontend tools are not
  accepted from browser configuration.

## Validation

```bash
JAVA_HOME=/path/to/java8 mvn clean test
```
