# union-control local workspace

The repository mirrors the production Web layout and keeps unavailable local
dependencies in a separate mock service:

```text
ark-web-server/  Web controllers, authentication, authorization and scheduler
service/         trusted JSON-input business services and database mappers
```

The production scheduled-task changes follow the existing layered packages:

```text
service/src/main/java/com/union/control/mapper/ScheduledTaskMapper.java
service/src/main/java/com/union/control/service/ScheduledTaskService.java
ark-web-server/src/main/java/com/union/control/scheduled/ScheduledTaskScheduler.java
ark-web-server/src/main/java/com/union/control/security/ScheduledExecution*.java
```

The Web `scheduled` package contains only the timer entrypoint. Mapper and
business service code remain in `service`; authentication code remains in Web.

Local-only authentication mocks live under
`ark-web-server/src/main/java/com/union/control/local`.
Never copy that package, the local `com.epcc.arkweb` stand-ins, or the local
`ShiroConfig` into production.
The service module must not contain any Spring MVC controller.

Production Shiro wiring is additive: register `ScheduledExecutionRealm` beside
the existing Ark Realm and place `ScheduledExecutionFilter` on `/agent/**`
before the existing `authc` filter, with Shiro's `noSessionCreation` enabled for
that Scheduled path. Do not replace either production CAS configuration.

`ark-web-server` resolves the Shiro subject through `AuthContextHolder`, replaces
any client identity fields, and serializes the backend request through
`AuthenticatedRequest`. Every user-facing business service accepts that one JSON
string and never reads Shiro or a cookie. All browser/internal HTTP controllers, including
`LlmController`, `AgentController` and `ScheduledTaskController`, live in
`ark-web-server/src/main/java/com/epcc/arkweb/web/llm`. The scheduled controller
uses the normal production service layer directly; no scheduled-package facade
or local adapter is introduced.

The following configuration is consumed by the assembled local application.

Configuration:

- `PY_APP_BASE_URL`
- `SCHEDULED_TASK_ENABLED` (default `false`)
- `SCHEDULED_TASK_WORKER_THREADS` (default `2`)
- `SCHEDULED_TASK_MAX_RUN_SECONDS` (default `930`)
- `SCHEDULED_TASK_TOKEN_TTL_SECONDS` (default `960`; must exceed the max run time)
- `AGENT_MAX_RUN_SECONDS` (default `900`)
- `AGENT_CANCEL_GRACE_SECONDS` (default `30`)
- `MYSQL_URL`, `MYSQL_USER`, `MYSQL_PASSWORD` (`MYSQL_URL` must use a UTC connection timezone)
- `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_TIMEOUT_MS`
- `SENSITIVE_REVEAL_TTL_SECONDS` (default `300`)

CAS and scheduled-execution API routes reuse production Apache Shiro permission
`@RequiresPermissions("/assistantManager/page")`. In the local environment, the
`LocalCasRealm` maps the valid same-origin `CASSESSIONID=session-1` session to
that permission; replace its local lookup with the production authentication-
service integration. Normal
control-to-py calls forward only the authenticated CAS session. If
`PY_APP_BASE_URL` is not loopback, use HTTPS with mTLS or an equivalent
authenticated service mesh.

The local database is initialized from
`service/src/main/resources/schema.sql`. The schema is a clean AG-UI/Memory
schema; there is no legacy compatibility layer.

Natural-language scheduled tasks use the two `agent_scheduled_task*` tables and
snapshot the authenticated CAS principal's `userId`, `orgCode`, and selected
Ark `roleId` on create and update.
The scheduler stores only the successful py-app result's `content` and
`agentName` on the run; opening an unread run atomically creates the normal
AG-UI conversation and messages.
Existing databases must apply
`service/deploy/sql/20260813_add_scheduled_execution_identity.sql`. The script
drops both pre-release scheduled-task tables and recreates them from the current
schema, so existing scheduled tasks and runs are intentionally discarded.
Each occurrence receives a 256-bit, short-lived token while the database stores
only its SHA-256 hash. Control calls py `/agent/v1/runs/scheduled` with only that
token and `{}`; py introspects it through `/agent/scheduledExecutionIdentity` and
reuses existing `/agent/*` tools with the same token. No tool endpoint is copied.
The Scheduled Realm restores the occurrence as the production `ShiroUser`
shape. It authenticates the one-time credential only; the existing production
Realm remains the single owner of role-resource lookup and authorization. No
mock permission provider or permission snapshot belongs to the production copy
set. Py validates only the scheduled identity contract and does not interpret
permission strings.
Each user may keep at most 100 `ACTIVE` or `PAUSED` tasks.

The isolated sensitive-data demo mirrors the production database boundary.
`POST /api/sensitive/demo` and `GET /api/sensitive/demo` use a MyBatis field
interceptor that encrypts before a database write and decrypts after a read.
The existing decrypt path exposes one hook; its reveal implementation replaces
the plaintext with a masked value plus a short-lived, owner-bound token. The
independent `POST /api/sensitive/reveal` API resolves that token and decrypts
the ciphertext without re-entering the masking hook. Send the local mock cookie
`CASSESSIONID=session-1`. The local crypto dependency remains Base64URL-only;
production supplies its existing AES implementation at the same boundary.

Run the Java 8 test suite with:

```bash
mvn clean test
```
