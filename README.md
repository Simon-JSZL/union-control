# Ark Agent applications

This repository builds one headless application, one production Web integration
module, and one shared Dubbo contract artifact:

```text
ark-control-facade/  serializable Dubbo interfaces shared by both applications
ark-control/         headless Dubbo provider, business services and database mappers
ark-web/             HTTP/SSE integration, Agent guard and Dubbo consumer
```

`ark-web` authenticates HTTP callers and owns `HttpServletResponse`. Streaming
requests go directly from `ark-web` to py-app so SSE has no Dubbo hop.
`ark-control` owns persistence and provides the remaining synchronous RPCs.

The main scheduled-task paths are:

```text
ark-control/src/main/java/com/union/control/mapper/ScheduledTaskMapper.java
ark-control/src/main/java/com/union/control/service/impl/ScheduledTaskServiceImpl.java
ark-control/src/main/java/com/union/control/schedule/ScheduledTaskScheduler.java
ark-control/src/main/java/com/union/control/schedule/ScheduledExecutionToken.java
ark-web/src/main/java/com/epcc/arkweb/web/llm/AgentAuthorizationInterceptor.java
ark-web/src/main/java/com/epcc/arkweb/web/llm/AgentController.java
```

Mapper, business service, token and scheduler code remain in `ark-control`;
browser authentication and the Agent-only permission guard remain in
`ark-web`.

The standalone module keeps the production class and bean names
`ShiroConfig`, `Realm`, `LoginFormFilter`, and `InterceptorConfig`, and follows
the same filter -> Realm -> Subject -> permission path. Its only local
replacement is the external `arkAuthService` bean under
`ark-web/src/local-mock/java`, inactive unless `local-auth-mock` is set.
Production keeps its existing Realm and filters.
The `ark-control` provider must not contain any Spring MVC controller.

Production Realm, filters and shared permission advisor remain unchanged. The two
Shiro configuration copies under `ark-web/src/production-overlay/java` add only
`/agent/** -> anon` before `/** -> authc`. `InterceptorConfig` registers
`AgentAuthorizationInterceptor` for `/agent/**` and excludes that path from the
browser Referer/session-IP interceptors. Apply these small changes to the host
configurations; do not register another Shiro config or an Agent servlet filter.

Every Agent handler declares one `@AgentPermission`. Missing annotations deny
access. CAS calls require an authenticated Subject and its native `checkPermission`;
Scheduled calls resolve their run token and check the saved permission snapshot.
Ordinary browser controllers retain `@RequiresPermissions` and the original advisor.

`ark-web` reuses production authentication through the existing Shiro flow.
Business requests retain their individual service contracts; only operations
that require ownership or stored execution identity receive those specific
fields from `AuthContextHolder`. Scheduled identity handling stays in the Agent
path. There is no shared identity-injecting JSON wrapper. Control services do
not read Shiro or cookies. All browser/internal HTTP controllers, including
`LlmController`, `AgentController` and `ScheduledTaskController`, live in
`ark-web/src/main/java/com/epcc/arkweb/web/llm`. The scheduled controller
uses the shared Dubbo facade directly; no scheduled-package facade or local
adapter is introduced.

The applications share the following deployment configuration where relevant.

Configuration:

- `PY_APP_BASE_URL`
- `DUBBO_REGISTRY_ADDRESS`
- `DUBBO_PROTOCOL_PORT` (provider, default `20880`)
- `DUBBO_CONSUMER_CHECK` (consumer, default `false`)
- `DUBBO_DIRECT_URL` (consumer, optional; local direct-connect example:
  `dubbo://ark-control.localhost:20880`)
- `DUBBO_CONSUMER_TIMEOUT_MS` (ordinary RPCs, default `10000`)
- `AGENT_MAX_RUN_SECONDS` (default `900`; set the same value on web, control,
  and py-app; Agent HTTP connect/read and proxy RPC timeouts use this duration)
- `LOCAL_AUTHORIZED_ROLE_ID` (local `arkAuthService` mock only, default `role-1`)
- `SCHEDULED_TASK_ENABLED` (production keeps scheduling disabled until rollout
  checks pass; the independently maintained local YAML may enable it)
- `SCHEDULED_TASK_WORKER_THREADS` (default `2`)
- `SCHEDULED_TASK_MAX_RUN_SECONDS` (default `930`)
- `SCHEDULED_TASK_TOKEN_TTL_SECONDS` (default `960`; must exceed the max run time)
- `MYSQL_URL`, `MYSQL_USER`, `MYSQL_PASSWORD` (`MYSQL_URL` must use a UTC connection timezone)

For local Dubbo without ZooKeeper, set `DUBBO_REGISTRY_ADDRESS=N/A` on both
applications and set `DUBBO_DIRECT_URL=dubbo://ark-control.localhost:20880` on
`ark-web`. Do not use `127.0.0.1` in Dubbo 2.6.9 direct URLs: that version
rewrites loopback to the machine's preferred network address, which may be a
VPN interface. Remove any per-interface `-Dcom.union.control.service.*` direct
URL VM options because they override `DUBBO_DIRECT_URL`.

The unavailable production authentication service has one local substitute at
`ark-web/src/local-mock/java/com/epcc/arkweb/mock/ArkAuthServiceMock.java`,
enabled only by `--spring.profiles.active=local-auth-mock`. Local browser
requests still pass through Shiro's filter, Realm, Subject and permission
advisor. It automatically establishes a normal local Shiro Subject using
`user-1` / `local-only` (override with `LOCAL_USER_ID` and `LOCAL_PASSWORD`),
so no local login page is required. Scheduled headers skip this auto-login and
remain on the non-Shiro Agent guard. The mock grants `/assistantManager/page`
only to `LOCAL_AUTHORIZED_ROLE_ID`.

CAS browser routes reuse production Apache Shiro permission
`@RequiresPermissions("/assistantManager/page")`. Normal
control-to-py calls forward only the authenticated CAS session. If
`PY_APP_BASE_URL` is not loopback, use HTTPS with mTLS or an equivalent
authenticated service mesh.

Production already maintains its own correct schema and configuration.
`ark-control/src/main/resources/schema.sql` is the local AG-UI, Memory, and
scheduled-task simulation reference. These local files and standalone JARs are
not production deployment inputs; see PROJECT_OVERVIEW.md for the integration boundary.

Natural-language scheduled tasks use `t_m_announce_scheduled_task` and `t_m_announce_scheduled_run` and
snapshot the authenticated CAS principal's `userId`, `orgCode`, and selected
Ark `roleId` on create and update. After the browser method permission check, Web
saves `["/assistantManager/page"]` as the task's permission snapshot. Permissions
and identity cannot be submitted through the browser command VO.
The scheduler stores only the successful py-app result's `content` and
`agentName` on the run; opening an unread run atomically creates the normal
AG-UI conversation and messages.
Production uses its DBA-maintained schema and separately managed configuration.
Each occurrence receives a 256-bit, short-lived token while the database stores
only its SHA-256 hash. Control calls py `/agent/v1/runs/scheduled` with only that
token and `{}`; py calls `/agent/scheduledTaskAuthorize`, which resolves the
active run through Control and checks the saved snapshot for the exact
`/assistantManager/page` permission. The response contains checked identity and
prompt only. Every subsequent tool call carries the same `Authorization: Scheduled`
token. No credential exchange, scheduled Realm or synthetic Subject is introduced.
Role revocation does not change saved permissions; editing a task refreshes them.
Old tasks without a snapshot fail closed and must be saved again.

This version requires one additional column in the DBA-maintained task table:

```sql
ALTER TABLE t_m_announce_scheduled_task
    ADD COLUMN permission_snapshot TEXT COLLATE utf8mb4_bin DEFAULT NULL;
```

Add the column with scheduling disabled before deploying the new mapper and Web
code. Do not backfill permissions for old tasks. The column can remain on rollback.
Each user may keep at most 100 `ACTIVE` or `PAUSED` tasks.

Streaming, sync, and scheduled py execution use `AGENT_MAX_RUN_SECONDS` (900
seconds by default). Sync and scheduled return HTTP 504 with `execution_timeout`
when the shared router-plus-Agent budget expires. Java uses the same setting
for finite HTTP connect/read waits and the Agent proxy Dubbo reference, replacing
the old fixed 120-second RPC wait. These are socket wait limits, not another
Agent lifecycle. Deploy the same value to all three processes; retain a larger
scheduled stale-run threshold and an even larger token TTL (defaults 930/960).

Run the Java 8 test suite with:

```bash
mvn clean test
```

Both applications are packaged independently with `mvn clean package`. Publish
`ark-control-facade` before deploying the provider and consumer. The facade
version used by `ark-control` and `ark-web` must match.

Do not install an HTTP or Dubbo filter that logs Agent arguments: they contain
the CAS cookie and model payload. Logs must also never contain SSE chunks.
