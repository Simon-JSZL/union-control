# 上线前整体审查 — 2026-09-08

复核结论（2026-09-08）：按项目负责人确认的生产源码集成方式，撤回 F01、F03、F04、F06、F07 的生产上线阻塞判定；F02 用生产 Spring 5.1.19 编译通过，撤回生产版本冲突判定。F05 已于 2026-09-09 修复：同步/定时总时限和 Java 传输超时统一使用 AGENT_MAX_RUN_SECONDS（默认 900 秒）。冗余、过度设计与历史残留清理已完成。F08–F11 未在本轮关闭，不能据此宣布全面通过上线检查。

审查基线：提交 `de55d63`，含工作区已有的 `package.json` 版本修改（1.1.6 → 1.1.7）。覆盖三个 Maven 模块、鉴权/身份传递、业务服务、Mapper XML、敏感数据处理、调度、生产 overlay、测试、配置和发布说明。基线共 118 个跟踪文件，其中 98 个 Java 文件、26 个测试目录文件。初次审查未修改业务代码；2026-09-09 按用户授权仅修复 F05 和下述清理项，保留用户已有修改及其他问题。

严重度：P1 为上线前应解决的高优先级问题；P2 为应修复的功能/安全缺陷或需要明确处置的依赖风险。没有把依赖版本命中等同于已完成远程利用验证。

## 验证结果

| 检查 | 结果 | 证据与范围 |
|---|---|---|
| Java 8 全量清理、编译、测试 | 通过 | Corretto 1.8.0_492；`mvn clean test`；control 83、web 30，零失败、零错误、零跳过 |
| 打包 | 通过 | 测试后运行 `mvn package -DskipTests`，核对两个可执行 JAR 内的配置、Mock 类和实际依赖 |
| 仅 Git 跟踪文件复测 | **失败** | 独立临时副本运行 `mvn -o test --fail-at-end`；2 个 control 错误、1 个 web 错误，均为缺少被忽略的文件 |
| 生产 InterceptorConfig 编译（复核） | **通过** | 原失败只发生于模拟 Spring 4.3.25；使用生产 Spring 5.1.19 编译当前 overlay，返回 0；三个缺失宿主拦截器仅提供类型桩 |
| 定向服务层验证 | 发现缺陷 | Memory 内容被 trim；空文件被拒绝；换用户后同 token 仍可 reveal；异常日志包含合成请求标记 |
| HTTP/py 超时修复 | 通过 | 同步/定时入口覆盖 router、root 和累计预算；Java 静默上游超时与 Dubbo 默认值/覆盖值绑定测试通过 |
| Python 全量测试 | 复测通过 | 184 passed、2 subtests passed；首次全量出现一项子 Agent 超时测试失败，单测及随后全量均通过，未改动该测试 |
| npm 发布清单 | 通过 | npm pack --dry-run --json 确认包含根 pom.xml；未发布 |
| 外部安全公告 | 部分核实 | 已读取 Apache Shiro 官方公告并比对 JAR 内 1.12.0 版本；没有执行全依赖 SCA |
| 实际生产联调 | 未执行 | 未连接 MySQL、Redis、ZooKeeper、CAS、生产 auth-service 或 py-app；未启动可能执行调度/建表的应用 |

日志：`/tmp/union-control-preflight-tests.log`、`/tmp/union-control-preflight-package.log`、`/tmp/union-control-preflight-tracked.log`、`/tmp/union-control-preflight-overlay.log`、`/tmp/union-control-preflight-probes.log`。验证用临时 Java 程序位于 `/tmp/UnionReviewProbe.java` 和 `/tmp/UnionLogProbe.java`，使用合成数据，未操作真实用户数据。

## 生产边界复核

### F01 · 撤回生产阻塞，保留本地可复现性说明

位置：[.gitignore:6](/Users/simon/code/union-control/.gitignore:6)。

生产已有正确且独立维护的 schema/configuration，不使用本机模拟文件。干净检出后的 3 项测试错误仍是事实，但只说明本地测试依赖未跟踪文件，不能推导为生产缺表或无法启动。本轮不要求把本机配置提交到 Git，也不再将此列为生产部署门禁。

### F02 · 生产版本冲突不成立，撤回

实际依据：[生产 Web 父 POM](/Users/simon/code/restored/ark-web/pom.xml:5)、[生产 InterceptorConfig](/Users/simon/code/restored/ark-web/ark-web-server/src/main/java/com/epcc/arkweb/config/InterceptorConfig.java:17)。

生产 Web 父 POM 是 Boot 2.1.18.RELEASE，其官方 BOM 管理 Spring 5.1.19.RELEASE；原始生产 InterceptorConfig 本身就直接实现 WebMvcConfigurer。旧报告把模拟工程的 Boot 1.5/Spring 4.3 当作生产依赖，适用对象错误。

已通过 Maven 离线生成父项目和 ark-web-server 的 effective POM，两者均确认 Spring MVC 5.1.19.RELEASE、Jackson Databind 2.17.2。结果为 `/tmp/union-production-web-effective-pom.xml` 和 `/tmp/union-production-web-server-effective-pom.xml`。这是依赖管理模型验证，不等于已解析全部私有依赖或完成宿主整体构建。

用实际 Spring 5.1.19 JAR 重新编译当前 overlay，仅为缺少的三个宿主拦截器提供空类型桩，javac 返回 0。**不需要将生产 overlay 改成 WebMvcConfigurerAdapter，也不应降级生产 Spring。**

编译验证日志：`/tmp/union-control-production-mvc-review.log`。

生产依赖核对：

| 依赖 | 当前模拟 Web | 生产 Web POM / BOM |
|---|---|---|
| Boot | 1.5.22.RELEASE | 2.1.18.RELEASE |
| Spring MVC | 4.3.25.RELEASE | 5.1.19.RELEASE |
| Jackson BOM | Boot 1.5 管理 | 显式覆盖为 2.17.2 |
| Shiro | 1.12.0 | 1.12.0 |
| Dubbo | 2.6.9 | 2.6.9 |
| Netty | 4.1.25.Final | 4.1.25.Final |
| ZooKeeper | 3.4.13 | 3.4.9 |

生产父 POM还显式管理 spring-context-support 4.3.20.RELEASE。这是宿主既有混合依赖，不能当作本次新增的冲突；后续应保留宿主管理并用实际依赖树判断，不应只看 Boot 版本推断全部依赖。

`restored/ark-control` 仅包含 dao/common 子模块及 POM，根父 POM 缺失，Spring/MyBatis 等版本不能解析。已请求提供根 POM 或 effective-pom；本轮没有声称完成整个生产 Control 的兼容性验证，也没有修改生产参考源码或擅自调整当前 POM。

### F03 · 撤回生产 Mock 风险判定

负责人确认生产排除全部 demo/mock 及本地兼容替身，使用真实认证、加密和业务服务；本地模拟 JAR 的 default profile 不会进入生产。因此非加密 Mock 和 RunningAnalysisMockService 不构成此次生产数据风险。

集成边界已记录：排除必须包括构造器依赖、Agent 工具的 Mock 调用、Dubbo 导出/引用和本地代理配置；正式工具接入生产已有实现。仅删 Mock 实现文件却原样复制相关控制器，不满足这一边界。此项是源码集成要求，不是对既定生产部署重新假设一项漏洞。

### F04 · 撤回生产 demo 入口风险判定

生产不会引入 demo 代码，因此原报告描述的越界通讯录 demo 接口不会部署。仍需正确理解“排除 demo”的范围：[SensitiveRevealController](/Users/simon/code/union-control/ark-web/src/main/java/com/epcc/arkweb/web/sensitive/reveal/SensitiveRevealController.java:24) 将正式 reveal 与 demo 方法、构造器依赖放在同一文件中；正式集成应只取 reveal 部分，排除所有 demo 方法及 SensitiveDataDemoService 注入。不能原样复制整个文件后只删除 DemoService 实现。

### F05 · 已修复（2026-09-09）

已对照 `/Users/simon/code/union-py-app` 的真实配置完成修复，未另建 Agent 生命周期：

- py 的共享 `sync_runs._sync_response` 用 `anyio.fail_after(runtime.settings.max_run_seconds)` 覆盖 router 与 root Agent 的累计运行时间；`/sync` 和 `/scheduled` 到期取消执行并返回 HTTP 504、`execution_timeout` 和安全错误文本。流式 ExecutionCoordinator 的已有总时限保持不变。
- Java `AgentProxyServiceImpl` 与 `LlmController` 的 HTTP connect/read timeout 使用 `AGENT_MAX_RUN_SECONDS`，默认 900 秒，向上取整转为毫秒，并拒绝非法时限。HTTP 超时限制单次 socket 等待，py 负责总执行时限。
- Agent proxy 的 Dubbo reference 从历史固定 120000 毫秒改为同一环境变量对应的毫秒值；生产集成时应同步该引用配置，不能只拷贝 Java 类。
- web、control、py 应设置相同 `AGENT_MAX_RUN_SECONDS`。调度 stale-run 与 token 默认余量仍为 930/960 秒；若调整 Agent 时限，生产配置需保持这两个值大于它。未修改现有鉴权、状态、工具 HTTP 时限和其他 RPC 配置。

验证：新增 6 个真实 ASGI 路由测试，覆盖 sync/scheduled × router/root/累计预算，修复前均失败、修复后通过；Java 使用本机静默 HTTP 服务验证 sync、scheduled、cancel 和流式代理的 socket 超时，并验证 Dubbo XML 默认 900000 毫秒与 2.5 秒覆盖值 2500 毫秒的实际绑定。Java 全量 113 项通过，Python 相关 75 项通过、全量复测 184 项及 2 个子测试通过。

首次 Python 全量中的 `test_delegation_timeout_is_a_reportable_failed_child` 失败（期望子 Agent 超时却观察到完成），该项单独复测和随后全量均通过。未为此修改无关业务或测试；这次复测不能证明已消除该测试的偶发失败。

验证日志：`/tmp/union-f05-java-full.log`、`/tmp/union-f05-python-full.log`（首次）、`/tmp/union-f05-python-recheck.log`（复测）、`/tmp/union-f05-package.log`。

### F06 · 撤回生产日志泄露判定

[ProviderAccessLogFilter](/Users/simon/code/union-control/ark-control/src/main/java/com/union/control/dubbo/ProviderAccessLogFilter.java:30) 的合成异常泄露实验仍真实，但生产已有自己的日志实现，不引用这个过滤器。因此不能据此认定生产泄露或要求替换生产日志。

PROJECT_OVERVIEW 已明确排除该类、SPI 注册及本地 dubbo-provider.xml 中的 filter 设置。此次没有验证生产日志实现，也没有重新要求用户证明已确认的部署边界。

### F07 · 撤回生产调度默认值风险判定

生产已有正确的独立配置，既不采用本机 application.yml，也不部署当前模拟 JAR；本地 `SCHEDULED_TASK_ENABLED:true` 和 `initialize:true` 不决定生产行为。保持生产原有配置和 DBA 建表流程，不再据此要求修改本地调度默认值或重建生产表。

## 其他功能与安全问题

### F08 · P2 · Reveal token 没有绑定数据所有者

位置：[SensitiveRevealServiceImpl:34](/Users/simon/code/union-control/ark-control/src/main/java/com/union/control/service/sensitive/SensitiveRevealServiceImpl.java:34)、[RedisRevealTokenStore](/Users/simon/code/union-control/ark-control/src/main/java/com/union/control/service/sensitive/RedisRevealTokenStore.java)。

Redis 只存 token hash → ciphertext；reveal 读取身份 JSON 后仅使用 token，不比较 userId、orgCode 或资源范围。服务层定向验证中，alice 与 bob 使用同一 token 都得到相同片段。Web 的通用权限检查仍存在，因此不能称为匿名解密漏洞；风险是另一名有助手权限的用户获得 token 后，可读取本不属于自己的数据。

旧 README 声明 token 为 owner-bound（本次已按实现纠正文档，未改变权限行为），实际测试甚至以 `ignored-by-first-version` 作为 userId。建议在 token 元数据中记录并校验所有者或明确的资源授权范围；如果产品确实选择可转授 bearer token，应明确记录该安全决策并修正文档。

### F09 · P2 · Memory 写入改变文件原文，并拒绝空文件

位置：[MemoryStoreServiceImpl:82](/Users/simon/code/union-control/ark-control/src/main/java/com/union/control/service/impl/MemoryStoreServiceImpl.java:82)、[AgentSupport.text](/Users/simon/code/union-control/ark-control/src/main/java/com/union/control/utils/AgentSupport.java:74)。

文件内容复用会执行 `trim()` 的字段校验。定向验证写入 `"  indented\n"`，Mapper 收到 `"indented"`；写入空串报 `content 非法`。这会改变代码缩进、Markdown 内容和后续精确编辑依据，违背文件存储应保留原文的行为。

建议：content 只做字符串类型和长度校验，保留首尾空白、换行与合法空内容。为这三种输入增加一次写读往返验证。

### F10 · P2 · 修改任务定义后，旧结果会配上新 prompt

位置：[ScheduledTaskMapper.xml:114](/Users/simon/code/union-control/ark-control/src/main/resources/mapper/ScheduledTaskMapper.xml:114)、[ScheduledTaskServiceImpl:282](/Users/simon/code/union-control/ark-control/src/main/java/com/union/control/service/impl/ScheduledTaskServiceImpl.java:282)。

运行记录不保存当次 prompt/title 快照，findOwnedRun 从当前任务表读取它们。任务以 prompt A 完成后，用户把任务改成 B，再首次打开旧运行结果，创建的对话就是“用户 B + 对 A 的回答”。后续追问会使用错误上下文。此问题由 SQL 和调用链确定，未做真实 MySQL 场景复现。

建议：在现有 run 表保存本次执行使用的 prompt/title 等必要快照；生成历史对话时读该快照。不要另建一套会话或执行路径。测试“完成 → 修改任务 → 打开旧结果”。

### F11 · P2 · 发布依赖含已公告安全问题，不能只靠单元测试放行

位置：[ark-web/pom.xml:19](/Users/simon/code/union-control/ark-web/pom.xml:19)、[ark-control/pom.xml](/Users/simon/code/union-control/ark-control/pom.xml)。

原检查的模拟 JAR 包含 Shiro 1.12.0、Spring MVC 4.3.25、Dubbo 2.6.9、Netty 4.1.25、Jackson Databind 2.8.11.3、Tomcat 8.5.43、SnakeYAML 1.17。**这些不能整体当作生产依赖清单**；生产 Web 已确认 Boot 2.1.18、Spring MVC 5.1.19、Jackson BOM 2.17.2，仍声明 Shiro 1.12.0。生产已有依赖的升级属于宿主维护，不能因本次功能集成擅自调整。其中已核对 [Apache Shiro 官方安全公告](https://shiro.apache.org/security-reports.html)：

- CVE-2023-46750：form authentication 的开放重定向；该仓库本地 Shiro 使用 FormAuthenticationFilter 路径，1.12.0 早于公告修复版本。
- CVE-2023-46749：与路径重写组合时的鉴权绕过；默认 blockSemicolon 可缓解。未验证生产是否满足利用条件，不能直接认定生产可绕过。

建议：对最终生产依赖树做 SCA，选择与宿主兼容且仍有安全维护的版本，验证 Shiro/CAS/反向代理路径组合。公告中的最低历史修复版本不是 2026 年的完整升级建议。其余旧依赖尚未逐项核对 CVE，也未进行网络利用测试。

## 冗余、过度设计与历史残留

本节列出的清理已于 2026-09-09 完成：

- `ScheduledExecutionToken.hash()` 复用 `hashSubmitted()`，删除重复 SHA-256 实现。
- 删除无调用的 `ConversationMapper.findMessage` 及对应 XML select。
- 删除 `nextForTask` 未使用的 `starting` 参数和调用处布尔实参。
- `conversationDetails` 只查询一次执行列表，供消息重建和 executions 返回共同使用；删除透传查询包装方法，并增加一次查询的回归验证。
- README 修正旧 demo 路径、虚构 CAS 登录示例、owner-bound 声明、Mock profile、调度配置与生产边界说明，删除无实现支持的 `SENSITIVE_REVEAL_STATEMENTS` 文档。同步敏感数据边界文档。
- npm `files` 补入根聚合 `pom.xml`，保留已有版本 1.1.7；使用本机 Node 22 的 npm dry-run 验证清单包含根 POM。清单仍包含本机模拟 application.yml/schema.sql，与既有打包行为一致；该包不是生产整体部署输入。本次未修改 F01/F07 的本地配置或打包排除策略，也未发布包。

没有新增依赖、缓存框架或执行状态机。保留 Dubbo facade、共享 Agent 入口、身份覆盖和 Memory 路径约束。F08–F11 的业务行为及依赖版本未修改。

## 验证缺口与放行条件

现有测试多为 Mockito 和源码字符串契约测试：Memory 仅 1 项测试，调度器仅 1 项；未覆盖真实 MySQL 锁、幂等并发、连接中断、敏感数据跨用户读取和完整生产宿主启动。测试通过证明的是当前测试范围内的行为，不是数据库和跨服务已经兼容。

上线前应依次完成：

1. 按已确认生产边界集成源码，使用宿主 POM、配置、schema 和日志；F01/F02/F03/F04/F06/F07 不再作为生产修复要求。Control 父 POM 的版本验证仍待补充。
2. F05 已完成本地修复与验证；将相同 AGENT_MAX_RUN_SECONDS 及 Agent proxy 引用超时集成到生产，并验证真实链路超时行为。
3. 修复 Memory 原文和运行历史快照问题；明确 reveal token 权限模型；完成依赖安全处置。
4. 在独立 MySQL 8 上用生产 schema 验证 Mapper SQL、并发 claim、Memory CAS/operation-id 冲突、重复打开结果；在真实 CAS/auth-service/py 链路验证撤权、过期、混合凭据和超时。
5. 按现有文档先发布 facade、再 provider/consumer；保持调度关闭，跨服务验证完成后再开启。验证关闭调度、排空运行和版本回滚流程。

额外架构确认项：scheduledTaskAuthorize 返回的 trustedContext 当前就是原 Authorization 字符串，工具入口只是换一个 header 后重新查库/查权限；它不提供独立的“已交换凭据”状态。现有实时资源检查仍有效，未证明权限绕过。若架构要求必须先经过交换端点，应在修复时明确该要求，避免仅靠 header 名称声称两种凭据已经隔离。
