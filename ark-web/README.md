# ark-web

This is the browser-facing module merged into the existing production Web
application. It owns the new HTTP controllers and final SSE response while
reusing the production Shiro Realm and Filter implementations.
Its standalone startup uses the production class and bean names
`ShiroConfig`, `Realm`, and `LoginFormFilter`, and follows
the same Shiro Filter -> Realm -> Subject -> permission path. Production uses
the host application's existing implementations.
Business and persistence services are consumed from
`ark-control` through `ark-control-facade` Dubbo references.

The production Web application remains Shiro authenticated. Task persistence,
due-run claiming, execution tokens, workers and model calls stay in the control
provider. There is no Web-only scheduled facade or local scheduled-service
adapter:

```text
ark-web/src/main/java/com/epcc/arkweb/
  vo/llm/ScheduledTaskCommandVO.java
  vo/llm/ScheduledTaskQueryVO.java
  web/llm/ScheduledTaskController.java
```

`ScheduledTaskController` uses
`com.union.control.service.ScheduledTaskService` from the shared facade. Its
implementation is published by `ark-control`.

`AgentController` keeps the production CAS path for interactive py calls.
Both production filter-chain maps use `/agent/** -> agentAuth` before
`/** -> authc`. `AgentAuthorizationFilter` protects every Agent URL with the
existing `/assistantManager/page` permission. It uses the current Subject for
CAS requests and validates the Control-issued token plus live role resources
for Scheduled requests. Scheduled credentials do not log in a Subject or create
a session. The filter is constructed inside the Shiro configuration, never
registered as a global servlet filter.

Scheduled entry and tool calls all carry `Authorization: Scheduled <token>`.
`scheduledTaskAuthorize` returns only the run identity already checked by the
filter, without returning a credential. There is no trusted-context header,
MVC Agent interceptor, or per-method Agent permission annotation.
The controller stays under `/agent/**`, not production's anonymous `/api/**`.

The copy-ready production files live under `src/production-overlay/java` and
are not part of the standalone compilation source set. Both Shiro files register
`agentAuth`; the `InterceptorConfig` copy only excludes `/agent/**` from the
browser-only interceptors. Copy these over the three matching production files.
Deploy the Web and py authentication changes together while scheduling is disabled;
the former trusted-context protocol is not supported. Keep browser authentication
and production auth-service implementations unchanged.

For local authentication testing, activate `local-auth-mock`. It establishes a
normal Shiro Subject automatically, so no login page or manual cookie is needed.
Authorization headers skip auto-login and continue through `agentAuth`. The only local replacement is the external `arkAuthService` bean at
`src/local-mock/java/com/epcc/arkweb/mock/ArkAuthServiceMock.java`; configure it
with `LOCAL_USER_ID`, `LOCAL_PASSWORD`, `LOCAL_ORG_CODE`, and
`LOCAL_AUTHORIZED_ROLE_ID`.

`ShiroUser`, `AuthContextHolder` and `ResultMsg` remain Web-owned types and do
not cross the Dubbo boundary.

The controller deliberately:

- follows the existing `web/llm`, `vo` and `service` package layout;
- exposes both `/llm/**` and `/union-op/llm/**` like the current production
  `LLMController`;
- uses the existing `/assistantManager/page` Shiro permission;
- gets `loginName`, `orgCode`, and the selected `roleId` only from the
  production Shiro identity exposed by `AuthContextHolder`;
- browser-facing methods never accept user identity, permissions or an
  execution token from the browser. The internal scheduled authorization method
  accepts only the Control-issued token from py-app.

Before enabling scheduling, verify the production `arkAuthService` bean is
available and fails closed when its backing service cannot be reached. The local
build has no proprietary auth-facade dependency, so the Agent-only guard invokes the unchanged production
`queryResource(roleId, userName, traceNo)` contract reflectively.

`LlmController#chatMessage` posts directly to py-app and writes and flushes each
upstream chunk to `HttpServletResponse`. The streaming path does not use Dubbo.

Production HTTP and Dubbo filters must not log Agent cookies, request payloads,
responses, or SSE chunks.
