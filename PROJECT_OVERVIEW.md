# Project Overview

## Mandatory Architecture Contract

This section is the highest-level implementation constraint for every future
feature iteration. New requirements must comply with it before lower sections,
existing scenario code, or tests are considered. Existing code that conflicts
with this contract is technical debt to remove, not a precedent to copy.

### Web authentication and JSON-only service boundary

- `ark-web` is merged into the existing production Web application. Browser
  routes reuse its existing Shiro/CAS implementation and authorization annotations.
  The standalone build mirrors the production `ShiroConfig`, `Realm`,
  `LoginFormFilter`, and `InterceptorConfig` names and lifecycle; only the
  external `arkAuthService` bean is replaced under `src/local-mock/java`.
  The production-overlay copies of both filter-chain configurations delegate
  only `/agent/**` to the Agent guard with `anon` before their catch-all `authc`.
  They replace the matching production files during integration; runtime source
  must not define another Realm, Shiro configuration, CAS token, or authentication filter.
- `ark-control` is a trusted, independently deployed Dubbo provider. Business services must not
  read Shiro subjects, cookies, thread-local authentication, or call helpers such
  as `currentUserId()`. The module has no Shiro dependency.
- After Web authentication succeeds, `AuthenticatedRequest` overwrites any
  client-supplied `userId`, `orgCode`, and `roleId` with the authenticated values
  and serializes the complete backend input as one JSON string.
- User-facing business service methods accept that JSON string, parse and validate
  it, then pass the parsed values to their mapper. Mapper ownership predicates
  continue to use `userId`; the service trusts the Web boundary that supplied it.
- Background scheduler mechanics that create or consume execution credentials
  live in `ark-control`. Backend-only scheduling state transitions may keep
  typed internal method parameters because they are not public request inputs.

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

- The Web application authenticates interactive execution and forwards the
  validated CAS cookie unchanged. Scheduled execution uses a separate
  single-occurrence, short-lived, sessionless token; Control stores only its
  SHA-256 hash and never constructs a Shiro identity for it.
- Scheduled traffic carries only `Authorization: Scheduled <token>`. It enters
  py through `/agent/v1/runs/scheduled`, then calls
  `/agent/scheduledTaskAuthorize`. Web resolves the active run through Control,
  calls the production `arkAuthService.queryResource` bean with the
  stored role ID, and requires `/assistantManager/page`. The response includes
  a database-backed trusted context for subsequent Agent tools. Those tools must
  use `X-Agent-Trusted-Context`; the raw scheduled bearer is not accepted on a
  tool route. The non-Shiro guard re-resolves the run and live resources for
  every tool call and never constructs a Subject.
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
MyBatis interfaces, application services, and timer entrypoints live in
`ark-control`; the Agent-only guard stays beside `AgentController`. Production
Shiro configuration, filters, Realm, and identity model are reused as-is.
Scheduled-task code must not introduce a parallel domain package containing its
own mapper, service, Web facade, production-authentication wrapper, or local mock.

### Production integration boundary and review baseline

Confirmed by the project owner on 2026-09-08: this repository is a local
simulation and a source integration workspace. Its standalone JARs, POMs,
configuration files, and Dubbo XML are not deployed wholesale to production.
Selected business changes are integrated into the existing production projects.

- Production already has the correct, independently maintained database schema
  and configuration. Local ignored `schema.sql` and `application.yml` files,
  local schema initialization, and local scheduler defaults are not production
  deployment inputs. Missing local files may affect clean-checkout testing,
  but must not be reported as missing production schema/configuration or as
  production scheduling being enabled without evidence from the integration.
- Production excludes all demo/mock implementations and their wiring. This
  includes local sensitive crypto/auth substitutes, compatibility copies,
  `SensitiveDataDemo*`, and `RunningAnalysisMockService*`. Production retains
  its real crypto, authentication, and business services. Exclusion also covers
  demo controller methods, constructor dependencies, mapper registrations, and
  Dubbo references/exports; removing only the implementation files is insufficient.
- Production already has its own complete Dubbo logging. It does not import
  `ProviderAccessLogFilter`, its SPI registration, or the local XML filter
  setting. Findings in this local logger do not establish a production log leak.
  Preserve the existing production logging implementation.
- Under this confirmed integration boundary, review findings F01, F03, F04,
  F06, and F07 from the 2026-09-08 audit are not production release blockers.
  Reopen them only if the actual integration starts importing the excluded
  sources/configuration or contradicts these assumptions. Review the selected
  production changeset, not a hypothetical deployment of this mock application.
- Implement new functionality against the production dependency versions;
  do not downgrade the host to match the local build or upgrade the host just
  to accommodate new code. Production reference sources are
  `/Users/simon/code/restored/ark-web` and
  `/Users/simon/code/restored/ark-control`; these are read-only reference inputs.
  The Web parent POM specifies Boot 2.1.18.RELEASE, whose BOM manages Spring
  5.1.19.RELEASE. Its Jackson BOM override is 2.17.2; Shiro is 1.12.0 and Dubbo
  is 2.6.9. The production `InterceptorConfig` already implements
  `WebMvcConfigurer`; the overlay compiles against Spring 5.1.19. F02's
  Spring-4-only compilation failure is not a production incompatibility.
- The restored Control snapshot currently contains only dao/common child
  POMs, with versions inherited from an absent root `ark-control/pom.xml`.
  Do not infer Control's Spring/MyBatis versions from the local simulation or
  the Web parent. Obtain its root/effective POM before claiming full production
  dependency compatibility. Host-specific dependency overrides take precedence
  over a Boot version alone.

### Cross-service timeout review

Inspect `/Users/simon/code/union-py-app` before assessing Agent timeout coverage.
Py owns Agent execution deadlines; Java transport timeouts are a separate
connection/resource safeguard, not another Agent lifecycle implementation.

`AGENT_MAX_RUN_SECONDS` is the shared execution duration, defaulting to 900
seconds. Set the same value in web, control, and py-app. Py's
`ExecutionCoordinator` applies it across streaming preparation and execution;
`sync_runs._sync_response` applies one `anyio.fail_after` scope across the shared
router and root Agent for both `/sync` and `/scheduled`, returning HTTP 504 with
`execution_timeout` on expiry. SSE comment keepalives remain every 15 seconds;
existing authentication, state, and tool HTTP timeouts remain unchanged.

Java Agent HTTP connect/read timeouts and the Agent proxy Dubbo reference use
this same duration (converted to milliseconds), instead of unbounded socket
waits or the historical 120-second RPC timeout. HTTP timeouts bound individual
socket waits; py remains responsible for the total execution deadline. No
second execution lifecycle is introduced. Preserve the scheduled stale-run
and token-expiry margins above that duration (defaults 930 and 960 seconds).
The F05 deadline gap is covered by sync/scheduled router, root, and combined
budget cancellation tests and Java silent-upstream tests.

## Purpose

`ark-web` is the browser-facing control plane for the PydanticAI service;
`ark-control` is its trusted Dubbo provider, and `ark-control-facade` is their
shared serializable contract. Together they own authenticated
conversation/run state, standard AG-UI message
persistence, the official Memory store protocol adapter, and transparent AG-UI
SSE proxying.

## Runtime

- Java 8
- Standalone simulation: Spring Boot 1.5.22.RELEASE / Spring 4.3.25.RELEASE
- Production Web: Spring Boot 2.1.18.RELEASE / Spring 5.1.19.RELEASE; use the
  production POM overrides described above. Production Control version is
  pending verification of its missing parent POM.
- MySQL 8
- Browser APIs remain under `/llm/**`
- Internal py-app APIs remain under `/agent/**`; they must not move under the
  production-anonymous `/api/**` namespace. Interactive calls validate the CAS
  session and scheduled calls use the dedicated token/context guard.

Only GET and POST endpoints are used in production.

Natural-language scheduled-task browser APIs also live under `/llm/**`.
They create `ONCE`, six-field Spring `CRON`, or fixed `INTERVAL` tasks and
expose task/run lists, unread results, and an explicit open-result action.
Draft generation is an ordinary browser call to `/llm/chatMessageSync`; there
is no scheduled-task-specific model endpoint.

## Protocol

- `POST /llm/chatMessage` accepts the standard AG-UI `RunAgentInput`, creates
  its persistence row, and transparently proxies AG-UI SSE.
- `GET /llm/conversationDetails` returns conversation metadata and a
  `messages` array of standard AG-UI messages.
- `POST /llm/executionCancel` requires `conversationId` and `runId` and forwards
  the owner-scoped cancellation request to py-app. Control does not maintain a
  second cancellation state.
- `POST /llm/chatMessageSync` keeps the product `{content}` response.

There is no execution-current/recovery endpoint, legacy event translation,
SDK item API, heartbeat event, or dual-write path.

## Persistence

`ai_conversation` owns only conversation metadata and status.

`ai_agent_execution` stores the root and child execution snapshots submitted by
py-app. Control does not enforce Agent concurrency or execution topology.

`ai_conversation_message` stores:

- message ID
- role and role-specific JSON payload
- an `agent_execution_id` foreign key
- a stable per-conversation sequence
- `delete_flag`

Browser history reconstructs each child execution as a standard AG-UI
`ActivityMessage`; model history contains root execution messages only.

Browser SSE disconnects forward cancellation to py-app. Py-app owns execution
timeouts, cancellation precedence, root/child lifecycle, and the final status;
control persists the final snapshot without maintaining a second execution
state machine.

`ai_memory_file` and `ai_memory_operation` implement PydanticAI Harness
`SearchableMemoryStore` semantics: bounded read/list/search, CAS versioning,
operation-ID idempotency, and operation fingerprint conflicts.

The local sensitive-data implementation mirrors the restored production
`AESInterceptor` and `SymmetricalSecurityUtils` contracts. Sensitive statements
route through general encryption, general decryption, or the AddressBook table
migration branch. The global reveal mode replaces sensitive plaintext fragments
with masks and short-lived Redis tokens. AddressBook result rows whose returned
`role` value is explicitly configured may remain plaintext; missing, unknown, or
invalid roles stay masked. The authenticated reveal API decrypts only the token's
fragment. The local simulation supplies a non-cryptographic `sensitiveProxy`
under the default or explicit `local-sensitive-mock` profile. This substitute
is excluded from production, which retains its existing real proxy.

`sensitive.reveal.strict_mode` defaults to false and must have the same value
on every Web and Control instance. When true, Control forces the existing
masking flow even if `sensitive.reveal.enabled` is false, and AddressBook ignores
its plaintext-role whitelist. Web requires a two-minute, user- and token-bound
image captcha before calling the unchanged reveal service. Captchas are generated
with the production Kaptcha `Producer` and stored only as an attribute of the
existing Shiro login session; no captcha Redis adapter, database table, or Dubbo
contract is added. A new image replaces the session's previous challenge and
each submission consumes it, including failed attempts. The local JVM lock does
not provide distributed atomic consumption: use session affinity for concurrent
requests, and do not claim cross-node exactly-once verification. The existing
production session DAO may itself use Redis; this feature does not replace it.
See `docs/sensitive-reveal-strict-mode.md` for the browser contract and rollout.

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
   time, atomically issues a token, and calls py's scheduled execution endpoint
   with `{}`. Control stores only the token hash and makes no permission
   decision. Web's `/agent/scheduledTaskAuthorize` resolves the run, calls the
   production `arkAuthService.queryResource` bean, and requires the
   exact `/assistantManager/page` resource. Agent tools then use a non-Shiro
   `@AgentPermission` guard with the returned trusted context; browser/CAS
   requests continue to use the production Shiro Subject.
3. Every terminal run is unread until opened. Successful py-app content is
   stored as bounded JSON; failed runs retain a bounded, structured, user-safe
   error code and message supplied by py-app. Neither
   creates a conversation in the background.
4. `scheduledTaskRunOpen` verifies the browser owner and locks the run. It
   idempotently creates one normal AG-UI conversation with exactly two trusted
   messages: the task prompt as the user message and the stored final content
   as the assistant message. It marks the result read and returns the existing
   conversation on repeated opens. Deleting that materialized conversation also
   logically deletes its claimed run record, so run history never links to a
   deleted conversation.

Only the successful result's `content` and `agentName` fields are persisted.
Provider messages and unknown response fields are discarded at the control
boundary. Failed callbacks accept only bounded error codes and single-line
user-safe messages produced by py-app; raw upstream bodies, stack traces, and
credentials are never persisted. Opening either terminal state creates the same standard
two-message conversation, so users can follow up on a result or a failure.

Every logical delete sets `delete_flag=0`; active rows use
`delete_flag=1`. The former message and personal-memory schemas are not
migrated or retained because the feature was not released.

## Security

- `ark-web` authenticates browser callers before creating the JSON string passed
  to `ark-control`; `ark-control` performs no authentication or permission
  decision of its own, including while scheduling.
- Scheduled-task browser routes authenticate the caller's same-origin
  `CASSESSIONID`; the controller never substitutes a synthetic browser identity.
  Apache Shiro enforces `@RequiresPermissions("/assistantManager/page")` on every
  CAS-protected API. The explicit `local-auth-mock` profile exercises the same
  Filter/Realm/Subject path and mocks only the Realm's auth-service dependency;
  production continues to use its unchanged Realm and Shiro configuration.
- Normal Agent, tool, history, completion, cancellation, sync, and Memory calls
  forward and validate that `CASSESSIONID`.
- Scheduled execution has no browser session and must not derive or fabricate a
  CAS Cookie from the task owner. Its token is checked against the active
  RUNNING occurrence in Control; Agent tools use only the trusted context
  returned by the dedicated authorization endpoint, recheck live resources, and
  do not construct a Shiro Subject.
- The production Shiro Filter and Realm implementations remain unchanged. Both
  SSO and non-SSO filter-chain maps place `/agent/** -> anon` before `/** -> authc`,
  delegating this exact namespace to the fail-closed Agent guard. Missing
  `@AgentPermission`, invalid credentials, mixed authentication, unavailable
  Control, or unavailable role-resource lookup all deny the request. `/api/**`
  anonymity is not used for Agent traffic.
- Conversation completion and cancellation validate user/conversation/run
  ownership.
- Memory paths must begin with `<authenticated-user>/personal/`.
- Agent, Skill, memory namespace, model, provider, and frontend tools are not
  accepted from browser configuration.

## Scheduled-task configuration

- `SCHEDULED_TASK_ENABLED` controls claiming and execution in `ark-control` and
  remains disabled until the database schema, dedicated Agent
  authorization endpoint, production role-resource adapter, and py scheduled
  adapter are deployed and cross-service security checks are complete.
- Worker tuning: `SCHEDULED_TASK_WORKER_THREADS` and
  `SCHEDULED_TASK_MAX_RUN_SECONDS`.

The scanner runs every five seconds, claims at most 20 tasks per pass, queues
at most 32 worker submissions, and rejects schedules more frequent than once
per minute. These implementation limits are fixed in code. Configurable defaults
are defined locally in `ark-control/src/main/resources/application.yml`;
production uses its separately maintained configuration. Keep the
scheduler disabled while deploying or rolling back incompatible control and
py-app versions.

## Scheduled-task deployment and rollback

Roll out in this order:

1. Leave scheduling disabled and verify the existing production schema with
   the DBA. Production already maintains the correct schema separately; the
   local `ark-control/src/main/resources/schema.sql` is a simulation reference,
   not a required production deployment input.
2. Copy the three files from `ark-web/src/production-overlay/java/.../config`
   over their matching production configurations. The two Shiro files differ
   from the restored originals only by `/agent/** -> anon` before `/** -> authc`;
   `InterceptorConfig` registers the Agent guard and excludes `/agent/**` from
   the browser-only Referer, session-IP, and CSRF interceptors. Deploy the guard
   using the existing `arkAuthService.queryResource` bean, then deploy the py
   scheduled adapter while scheduling remains disabled.
3. Deploy the web UI and verify draft, create, update, list, pause/start, and result
   APIs while no background run can be claimed.
4. Enable scheduling on control only after the cross-service contract and
   database schema are verified.

Roll back in this order:

1. Disable scheduling on every control instance and restart or drain them so
   no new run is claimed; allow or explicitly terminate in-flight work.
2. Roll back the web UI, then control, then py-app. The scheduled-task tables
   may remain in place.
3. If the tables must be removed, drop `agent_scheduled_task_run` first and
   `agent_scheduled_task` second because of the foreign key.

## Deployment boundary

Publish `ark-control-facade` first. `ark-control` registers provider services in
ZooKeeper, and `ark-web` consumes them. The SSE `chatMessage` path goes directly
from `ark-web` to py-app and immediately writes and flushes each upstream byte
chunk to the browser. Non-stream Agent operations remain synchronous Dubbo RPCs.

HTTP and Dubbo filters must not log Agent arguments or stream data. They include
the CAS cookie and model payload. During rollback, stop or
roll back `ark-web` consumers before rolling back `ark-control` or its facade.

## Validation

```bash
JAVA_HOME=/path/to/java8 mvn clean test
```
