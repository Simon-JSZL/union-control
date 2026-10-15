# Project Overview

## Mandatory Architecture Contract

This section is the highest-level implementation constraint for every future
feature iteration. New requirements must comply with it before lower sections,
existing scenario code, or tests are considered. Existing code that conflicts
with this contract is technical debt to remove, not a precedent to copy.

### Web authentication and existing business service contracts

- `ark-web` is merged into the existing production Web application. Browser
  routes reuse its existing Shiro/CAS implementation and authorization annotations.
  The standalone build mirrors the production `ShiroConfig`, `Realm`,
  and `LoginFormFilter` names and lifecycle; only the
  external `arkAuthService` bean is replaced under `src/local-mock/java`.
  The production-overlay copies of both filter-chain configurations delegate
  only `/agent/**` to the `agentAuth` Shiro filter before their catch-all `authc`.
  They replace the matching production files during integration; runtime source
  must not define another Realm, Shiro configuration, or CAS token. The
  Agent-only `AgentAuthorizationFilter` is registered inside the existing Shiro
  chain, never as a global servlet filter.
- `ark-control` is a trusted, independently deployed Dubbo provider. Business services must not
  read Shiro subjects, cookies, thread-local authentication, or call helpers such
  as `currentUserId()`. The module has no Shiro dependency.
- Production already provides the complete browser authentication and
  authorization flow. Scheduled-task authentication changes must be minimal and
  confined to the `/agent/**` authentication path and its required Shiro wiring;
  ordinary controllers must not acquire Scheduled identity-selection logic.
- Preserve each existing business service's parameter names, types, and format.
  Do not introduce a shared request-to-JSON wrapper such as `AuthenticatedRequest`,
  require JSON-only service inputs, or inject `userId`, `orgCode`, and `roleId`
  into all business requests as part of authentication work. Scheduled identity
  fields serve authentication and authorization; they do not establish business
  input requirements. Pass identity fields only where the specific existing
  business contract requires them, preserving trusted identity sourcing and
  ownership checks for those operations.
- Background scheduler mechanics that create or consume execution credentials
  live in `ark-control`. Their existing method contracts remain unchanged by
  Web authentication changes.

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
  stored role ID, and requires `/assistantManager/page`. The response contains
  database-backed run identity and prompt, without returning a credential.
  Every subsequent tool call carries the same `Authorization: Scheduled <token>`.
  The filter re-resolves the run and live resources on every request; the Shiro
  permission annotation handler checks the endpoint's `@RequiresPermissions`
  against that resource list. Scheduled credentials never log in a Shiro Subject
  or create a user session.
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
browser filters, Realm, and identity model are reused as-is. The existing Shiro
configurations register the Agent-only filter for `/agent/**`.
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
  includes local authentication substitutes and `RunningAnalysisMockService*`.
  Production retains its real crypto, authentication, and business services. Exclusion also covers
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

### Retired sensitive feature

The sensitive feature was retired from this workspace on 2026-10-13.
Its implementation and tests remain in Git history at commit `9e5fed9`;
future production maintenance does not use this workspace version.

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

### Agent authentication decision (2026-10-14)

Replace the MVC Agent interceptor and method-level `AgentPermission` annotation
with a Shiro authentication filter: `/agent/** -> agentAuth`. Keep standard
method-level `@RequiresPermissions` on every Agent endpoint; current endpoints
require `/assistantManager/page`, and individual endpoints may declare different
permissions. CAS requests use the existing authenticated Subject and native Shiro
annotation checks. Scheduled requests use the existing run token and database
identity; the filter loads live resources through `arkAuthService.queryResource`
on each request, and the existing Shiro advisor uses that request's resource list
to check the same method annotation (including AND/OR). Resource URLs are matched
exactly. Only the permission annotation handler is extended; other Shiro
annotations retain their native checks. No scheduled Realm or synthetic login is
introduced. Remove the trusted-context header and credential exchange. The authorization endpoint only
returns the identity checked by the filter after its method permission check
and remains Scheduled-only.
Control retains task/run persistence and token lifecycle; py retains the existing
non-stream `/agent/v1/runs/scheduled` adapter and shared synchronous execution,
specialists and tools. Browser entrypoints and CAS forwarding remain unchanged.

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
  session and scheduled calls use the Agent authentication filter.

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
  Model-provider 401/403 failures use HTTP 502 with the existing bounded
  error/code/model fields; caller authentication failures retain 401/403.
  The non-stream Java proxy preserves Py's JSON error response and status.

There is no execution-current/recovery endpoint, legacy event translation,
SDK item API, heartbeat event, or dual-write path.

## Persistence

The seven Agent tables use `t_m_announce_` followed by two name segments.
Relationships use logical references without physical foreign keys; primary keys,
business unique constraints, and query indexes are retained. Services maintain
related writes and logical deletes within their existing transactions.

Local operations tools reuse `RunningAnalysisMockService`, its existing Dubbo
registration and the authenticated `AgentController` routes. `queryChanges` and
`queryFaults` accept combined current/history/detail filters. The seven
`t_indicator_*` interface names use the existing `queryBigData` route with
separate table records and paginated returns matching py-app's contracts.
`OperationsMockQueries` reads only the synthetic `mock/operations-v1.json`
resource, whose field shapes follow the supplied production SQL/detail samples.
The fixed analysis sample covers five institutions from 2026-09-01 through
2026-10-04, with date-keyed running counts, linked change/fault scenarios and
daily capability snapshots. Chain metrics aggregate the same member rows.
Mock duration units are milliseconds, without asserting production units.
July legacy scenarios remain available; dates outside the fixture are not
generated on demand.
New results explicitly report partial, mock coverage; unsupported filters and
historical visibility fail rather than being ignored. No production SQL or new
persistence is introduced. Production must replace the mock implementation and
exclude its fixture, as with the existing running-analysis mocks.
See `docs/operations-mock-contract.md` for mappings, sample scope and validation.

`t_m_announce_ai_conversation` owns only conversation metadata and status.

`t_m_announce_ai_execution` stores the root and child execution snapshots submitted by
py-app. Control does not enforce Agent concurrency or execution topology.

`t_m_announce_ai_message` stores:

- message ID
- role and role-specific JSON payload
- an `agent_execution_id` logical reference
- a stable per-conversation sequence
- `delete_flag`

Browser history reconstructs each child execution as a standard AG-UI
`ActivityMessage`; model history contains root execution messages only.

Browser SSE disconnects forward cancellation to py-app. Py-app owns execution
timeouts, cancellation precedence, root/child lifecycle, and the final status;
control persists the final snapshot without maintaining a second execution
state machine.

`t_m_announce_memory_file` and `t_m_announce_memory_operation` implement PydanticAI Harness
`SearchableMemoryStore` semantics: bounded read/list/search, CAS versioning,
operation-ID idempotency, and operation fingerprint conflicts.

`t_m_announce_scheduled_task` and `t_m_announce_scheduled_run` persist task definitions
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
   exact `/assistantManager/page` resource. Agent tools carry the same Scheduled
   token through `agentAuth`; browser/CAS
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

- `ark-web` reuses production authentication and authorization before invoking
  `ark-control` through the existing business service contracts; `ark-control`
  performs no authentication or permission decision of its own, including while
  scheduling. Authentication does not impose a common business payload format.
- Scheduled-task browser routes authenticate the caller's same-origin
  `CASSESSIONID`; the controller never substitutes a synthetic browser identity.
  Apache Shiro enforces each API's `@RequiresPermissions` using the logged-in
  Subject; existing browser APIs require `/assistantManager/page`. The explicit
  `local-auth-mock` profile exercises the same Filter/Realm/Subject path and mocks only the Realm's auth-service dependency;
  production continues to use its existing Realm and browser authentication chain.
- Normal Agent, tool, history, completion, cancellation, sync, and Memory calls
  forward and validate that `CASSESSIONID`.
- Scheduled execution has no browser session and must not derive or fabricate a
  CAS Cookie from the task owner. Its token is checked against the active
  RUNNING occurrence in Control; Agent tools use the same Scheduled token,
  recheck live resources, and
  do not authenticate a Shiro Subject.
- The production browser authentication filters and Realm remain unchanged. Both
  SSO and non-SSO filter-chain maps place `/agent/** -> agentAuth` before `/** -> authc`.
  The Agent filter validates credentials and loads live Scheduled resources;
  method-level `@RequiresPermissions` controls access through the existing Shiro
  advisor. Every Agent endpoint must declare its permission, enforced by a
  controller contract test. Invalid credentials, mixed authentication, unavailable
  Control or role-resource lookup, and missing required permissions deny access.
  `/api/**` anonymity is not used for Agent traffic.
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
   over their matching production configurations. The two Shiro files register
   the Agent filter and map
   `/agent/** -> agentAuth` before `/** -> authc`, and use `AgentAuthorizationAdvisor`
   for the existing permission annotation advisor bean;
   `InterceptorConfig` excludes `/agent/**` from
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
3. If the tables must be removed, drop `t_m_announce_scheduled_run` and
   `t_m_announce_scheduled_task`; neither has a physical foreign key.

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
