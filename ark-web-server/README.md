# ark-web production changes

This module is the production-shaped Web project. Its paths intentionally
mirror `ark-web/ark-web-server`; it is not an overlay directory.

The production Web application remains Shiro authenticated. Task persistence,
due-run claiming, execution tokens, workers and model calls stay in the normal
service layer. There is no Web-only scheduled facade or local scheduled-service
adapter:

```text
ark-web-server/src/main/java/com/epcc/arkweb/
  vo/llm/ScheduledTaskCommandVO.java
  vo/llm/ScheduledTaskQueryVO.java
  web/llm/ScheduledTaskController.java
```

`ScheduledTaskController` uses
`com.union.control.service.ScheduledTaskService`, matching the production
service package. If production binds it through Dubbo, that binding remains in
the existing service layer rather than a scheduled-package wrapper.

The local build gets production-owned `ShiroUser`, `AuthContextHolder` and
`ResultMsg` stand-ins from the top-level `service` module with `provided`
scope. Do not copy those stand-ins to production; ark-web already owns them.

The controller deliberately:

- follows the existing `web/llm`, `vo` and `service` package layout;
- exposes both `/llm/**` and `/union-op/llm/**` like the current production
  `LLMController`;
- uses the existing `/assistantManager/page` Shiro permission;
- gets `loginName`, `orgCode`, and the selected `roleId` only from the
  production Shiro identity exposed by `AuthContextHolder`;
- never reads a Cookie header or accepts user identity, permissions or an
  execution token from the browser.

Before enabling scheduling, verify the production service implementation is
available and fails closed when its backing service cannot be reached.
