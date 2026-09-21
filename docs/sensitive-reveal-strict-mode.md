# 严格脱敏模式接入

## 配置与范围

Web 和 Control 的所有实例统一配置：

```properties
sensitive.reveal.strict_mode=true
```

本地两个应用也支持环境变量 `SENSITIVE_REVEAL_STRICT_MODE=true`。
缺省或 `false` 保持原有行为；非法布尔值在配置注入时失败，不回退到普通模式。
该配置在启动时读取，修改后需重启。

严格模式下，Web 在调用现有 reveal 服务前校验验证码；Control 强制使用已有脱敏流程，
并忽略 addressbook 的 `getPlaintextRoles()` 返回的白名单。
即使 `getRevealEnabled()` 返回 false，严格模式仍对原拦截范围内的查询脱敏。
脱敏字段、正则匹配范围、Mapper 路由和加密算法不扩展。

配套前端位于 `../union-web`，已接入公共验证码弹窗和模式查询；联调页面为
`http://127.0.0.1:8089/sensitive-demo.html`。后端记录和 AddressBook 使用真实接口，
页面内标注为本地示例的固定数据仍不依赖后端。
不增加验证码数据库表、Redis 操作、Dubbo 方法或模型调用。
原有 reveal token 仍使用既有 Redis 密文存储。

## 前端调用顺序

所有请求沿用已登录浏览器的同源 Cookie，并保留宿主已有 CSRF/Referer 处理。
三个接口都要求现有 `/assistantManager/page` 权限。

### 1. 查询模式

`GET /api/sensitive/reveal/options`

```json
{"strictMode": true}
```

普通模式点击后直接提交 `{token}` 到 reveal；严格模式点击后先取图片。
模式查询失败不能自行当作普通模式，前端应提示重试。服务端独立检查自身配置，
客户端传入的 `strictMode=false` 等字段不能取消校验。

### 2. 获取或刷新图片

`POST /api/sensitive/reveal/captcha`

```json
{"token": "rt_..."}
```

成功响应为 `200 image/png` 的二进制 PNG，不是 JSON，也不返回答案或 `captchaId`。
失败响应为 JSON；前端应先判断 HTTP 状态再读取 Blob。
请求的 `Accept` 应允许图片和 JSON（或使用默认 `*/*`）。

```javascript
const response = await fetch('/api/sensitive/reveal/captcha', {
  method: 'POST',
  credentials: 'same-origin',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ token }),
});
if (!response.ok) {
  // 由宿主统一处理登录失效及非 JSON 错误，业务错误读取 code。
  throw new Error('获取验证码失败，请重试');
}
const imageUrl = URL.createObjectURL(await response.blob());
// 将 imageUrl 赋给弹窗内 img 的 src。
// 更换图片或关闭弹窗时调用 URL.revokeObjectURL(imageUrl)。
```

同一会话只保存一个待验证挑战，新图替换旧图；多个标签页同时取图会覆盖。
不要并发刷新，等待当前取图请求完成后再允许下一次刷新。
验证码从生成完成起两分钟有效。取图只检查 token 的格式，不查询 Control；
token 是否仍有效由最终 reveal 检查，取图不会延长 token 的原有效期。

### 3. 输入后查看明文

`POST /api/sensitive/reveal`

```json
{"token": "rt_...", "captchaCode": "A7K9"}
```

输入忽略首尾空白和字母大小写。验证码绑定当前登录用户、当前会话和指定 token。
每次提交取出并清除验证码，错误、缺码、token 不匹配也消耗本次挑战。
验证码通过后，Web 只将 token 和可信身份交给 Control，不转发 `captchaCode`。

成功结构与原来一致：

```json
{"value": "13800138000"}
```

明文只替换当前被点击的片段；不要写入浏览器持久存储或日志。
成功或取消时清空输入；验证失败时清空输入并重新取图。
如果 Control 返回 token 过期，刷新原查询重新获得脱敏 token。
验证码通过后即已消耗，即使后续 Control 失败，重试也需要新图。

## 新增业务错误

错误结构为 `{"code":"..."}`，不包含答案、明文或内部异常。
图片、明文、模式及验证码错误响应均禁止缓存。

| HTTP | code | 前端处理 |
| --- | --- | --- |
| 400 | `INVALID_TOKEN` | token 格式非法，刷新数据 |
| 403 | `CAPTCHA_REQUIRED` | 没有可用挑战，重新取图 |
| 403 | `CAPTCHA_EXPIRED` | 超过两分钟，重新取图 |
| 403 | `CAPTCHA_INVALID` | 输入错误、缺失或用户/token 不匹配，重新取图 |
| 409 | `STRICT_MODE_DISABLED` | 当前模式未开启，重新查询模式 |
| 503 | `CAPTCHA_UNAVAILABLE` | 图片生成失败，允许重试 |

现有登录、权限、token 过期及解密异常继续由宿主原有处理负责。

## 生产接入与会话边界

生产业务代码新增 `SensitiveCaptchaService`，修改 `SensitiveRevealController`、
`AESInterceptor` 和 `AddressBookHandler`。合入 controller 时仍排除所有 demo 方法及其依赖。
Control reveal 服务仅补充请求追踪日志，解密处理逻辑保持不变；
Facade、Redis token store、数据库和加密工具不变。

生产 Web 已有 Kaptcha 2.3.2 和 `Producer` Bean，直接复用；
本地 `LocalKaptchaConfiguration` 及本地 POM 补充不作为生产集成文件。
已对照生产版本的 Kaptcha `Producer` 和 `DefaultKaptcha` 源码，
使用 `createText()`、`createImage(text)` 和 JDK `ImageIO.write(..., "png", ...)`。

验证码保存在现有 Shiro Session 的独立属性，不使用登录验证码原有的 `code` 属性。
会话数据使用可序列化对象，并额外绑定登录名以防同一会话切换用户后沿用旧挑战。
生产参考配置的 Session DAO 本身使用 Redis；此功能复用它，不增加业务级 Redis 存取。

本实现仅在当前 Web 实例内串行执行验证码 Session 更新。多实例应使用会话粘滞，
且现有 Session DAO 必须及时反映会话属性的删除；普通 Session 读写不提供跨节点
原子消费，不能声称分布式严格一次性校验。图形验证码用于增加人工操作步骤，
不改变现有查看权限，也不为原 reveal token 增加创建者归属校验。

部署先完成前端弹窗和错误处理，再发布 Web/Control 代码；默认保持严格模式关闭。
启用时统一更新所有实例并重启，避免新旧配置混跑；刷新已打开页面的查询结果。
回滚需同步恢复两层配置并重启，再回滚代码。关闭严格模式会恢复原有直接查看及角色白名单行为。
日志系统继续禁止记录验证码请求体、PNG、Session 内容及明文响应。

## 日志排查

Web reveal 在接收请求、进入 Control、完成或拒绝时输出 INFO 日志；
服务端生成的 `request_id` 通过 JSON 的 `revealRequestId` 传到 Control，用于关联两端日志。
Control 输出接收请求、token 校验/查找结果、解密结果和完成耗时。
常规校验失败不输出 ERROR 或异常堆栈。

验证码日志记录 `session_ref`（Session ID 的 SHA-256 截断摘要）、随机 `challenge_ref`、
生成耗时及校验结果，不记录真实 Session ID、验证码、token、用户身份或明文。
`reason` 区分 `no_session`、`challenge_missing`、`expired`、`user_mismatch`、
`token_mismatch`、`answer_missing` 和 `answer_incorrect`。

如果提交始终返回 `CAPTCHA_REQUIRED`，先检查生成与提交的 `session_ref` 是否一致。
开发 `/api` 代理不能覆盖浏览器 Cookie；修改 `webpack.config.js` 后须重启
webpack-dev-server，模块热更新不会替换已经创建的代理中间件。单纯刷新页面不够。

## 验证

使用 Java 8：

```sh
mvn -Dtest=AESInterceptorCompatibilityTest,SensitiveRevealControllerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

覆盖普通模式兼容、严格模式强制脱敏、所有 addressbook role、PNG 输出、验证码错误/过期/
重复使用/跨用户/跨 token/跨会话、刷新覆盖、图片生成失败及验证码不进入 Dubbo 参数。
