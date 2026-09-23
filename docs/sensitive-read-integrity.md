# 敏感查询字段、行数与性能方案

## 已确认的事实和现场问题边界

2026-09-23 对当前工作区未提交修改完成源码核对与回归验证。

- 脱敏目标 `ValueTarget` / `Plan` 是字段级处理项，不是返回行。100 行各 3 个字段可以有 300 个处理项，但返回集合必须仍为 100 行。
- MyBatis 3.4.6 的 `MapperBuilderAssistant.getStatementResultMaps()` 会为 XML `resultType` 创建内联 `ResultMap`。复制原有 `ResultMap` 会保留类型、显式映射和 TypeHandler；不应凭截图推断重新构造映射。
- `StringTypeHandler` 通过 `ResultSet.getString()` 读值，BIGINT 列映射到父类 String ID 本身不是 ID 丢失的依据。本次真实 MyBatis 结果映射测试覆盖了这一组合。
- `getBoundSql()` 构建 SQL 和参数映射，不执行 JDBC 查询。原代码有重复构建 SQL 的开销，本次消除；它不是三倍结果行数的证据。
- 本地未复现生产截图所述的重复行/空 ID，不能声称已修复其尚未定位的现场根因。截图中的多次“最终定位”相互撤回，不能替代实际 Mapper、运行版本及执行结果。

核对的官方源码：

- [MapperBuilderAssistant，MyBatis 3.4.6](https://github.com/mybatis/mybatis-3/blob/mybatis-3.4.6/src/main/java/org/apache/ibatis/builder/MapperBuilderAssistant.java)
- [StringTypeHandler，MyBatis 3.4.6](https://github.com/mybatis/mybatis-3/blob/mybatis-3.4.6/src/main/java/org/apache/ibatis/type/StringTypeHandler.java)

## 已实现的方案

### 保持查询结果结构

`AESInterceptor` 返回 Executor 的原结果集合。`SensitiveRevealProcessor` 只原地修改指定 Map key 或带敏感注解的 String 字段，不重新构造 DTO，不新增、删除、去重或排序业务行，不改普通 ID/业务字段。

`AddressBookHandler` 不再复制结果并拆成两个列表；一次遍历按原有 strict/reveal/role 规则选择明文或脱敏。同一个对象出现多次时，使用对象身份集合仅转换一次，但保留所有原始列表位置。不能使用按 ID 的去重来掩盖 JOIN 或映射错误。

`AESInterceptor.copyBoundSql()` 使用一次构建的 BoundSql 进行换表；继续复用原结果映射，保留原参数、参数附加值及现有 statement 元数据。二级缓存禁用和查询后一级缓存清理保持原有安全边界，避免复用已改成显示标记的缓存对象。

### 消除重复远程操作

每次 `process` / `decrypt` 创建独立的 `ReadContext`，调用结束后不再保留：

1. 同 codec、同密文只解密一次；标准、带标签、分块三种 codec 分开缓存，避免串用。
2. 同一查询内失败结果也复用，防止大量相同坏密文重复访问密码服务或刷日志。下一次查询重新尝试。
3. 标准字段解密后若恰好是一个完整敏感片段，直接将原密文用于查看 token。它与查看接口的 `decryptWithCheckNoLog` 使用相同存储 codec，无需先解密再重复加密。
4. 带标签、分块或包含普通文字的字段，不把整个字段密文作为单个片段 token。每个不同片段按原 codec 加密一次；同片段复用 token，普通文字和字段顺序保持不变。
5. 相同片段在同一查询中只生成一个 token、写入一个 Redis key；不同查询生成新的 token，不使用跨请求明文缓存，也不共享进程级 token 缓存。
6. 邮箱匹配优先于手机号匹配，避免 `13812345678@example.com` 被拆成手机号和剩余文本。

Redis 每批 20 项的现有接口不变。没有引入线程池、批量 RPC 假设或新的远程依赖。对全部不同的密文，单次解密仍是必要成本；实际延迟仍取决于真实密码服务和 Redis，不能拿 mock 测试耗时作为生产 SLA。

### 保持写入和失败边界

- 未改写入拦截流程、原加密算法、密钥选择或生产通讯录手动加密边界。
- 查看 token 中保存密文，不保存明文；只有完整标准字段才复用原密文。
- Redis 不可用时仍返回不可点击脱敏标记；单片段加密失败不生成可点击 token；解密失败保持脱敏或按原明文路径报错。
- 重复值复用 token 后仍能按原规则逐字段还原；过期/无效标记不能写入，写入失败不执行 SQL，调用者参数和生成主键仍保留。
- 日志不记录敏感值、密文、token、SQL 参数；重复失败只记录一次 codec 和异常类型。

## 已验证的数量边界

以下均不含查询条件加密；三个字段分别为一个完整的标准敏感值，Redis 正常。

| 100 行 × 3 字段 | 修改前解密 | 修改后解密 | 修改前再加密 | 修改后再加密 | Redis 批次数，前→后 | 返回行数 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 300 个不同值 | 300 | 300 | 300 | 0 | 15→15 | 100 |
| 每行重复同样三个值 | 300 | 3 | 300 | 0 | 15→1 | 100 |

这属于调用次数断言，不是模拟网络延迟的估计值。身份去重只避免重复转换同一对象；不同业务行即使值相同也全部保留。

## 回归测试

Java 8 下运行：

```sh
mvn -pl ark-control -am -Plocal-sensitive-compat,sensitive-tests clean test
```

2026-09-23 初次查询优化实测：72 项测试，0 失败、0 错误、0 跳过。后续 D3 验证见下节。

- `SensitiveReadJdbcTest`：真实 XMLMapperBuilder、Executor、StatementHandler、ResultSetHandler；JDBC 连接为 mock，ResultSet 使用 JDK CachedRowSet。覆盖 `resultType`、显式 `resultMap`、Map 返回结果，父类 String ID / BIGINT 列、普通字段、时间字段、三个敏感字段；明文和脱敏模式各保持 100 行、ID 集合和顺序；每次查询只执行一次 JDBC statement。
- `AESInterceptorTest`：Map/实体结果保持原集合及原行引用；100 行三个重复字段的远程调用计数。
- `AddressBookHandlerTest`：strict/reveal/role 规则、跨明文/脱敏行共享去重、动态 SQL 只构建一次。
- `SensitiveRevealProcessorTest`：300 个不同值、查询间隔离、重复引用、codec 隔离、文本片段去重、原密文复用、失败缓存及下一请求重试、手机号式邮箱。
- 原有真实 MyBatis 写绑定/JCE AES 测试、token 回写、过期和 Redis 故障、SIMPLE/REUSE/BATCH Executor 测试继续通过。

## 生产集成与现场根因定位

本仓库为源码集成工作区。生产 MyBatis/分页插件版本、Mapper XML、完整 DTO 与 SQL 数据不在当前输入中；本地通过不是现场验收替代品。

合入时同步三个业务文件：`AESInterceptor.java`、`AddressBookHandler.java`、`SensitiveRevealProcessor.java`，适配生产包名，保留生产真实 crypto/Redis 实现。不要合入 local mock、demo Mapper 或模拟配置。保留前一轮写入完整性改动；不需要改表、修改 DTO 的 ID 类型或重建 resultMap。

截图提及 `getMaskedAddressBookListByCondition`，当前仓库 ADDRESS_BOOK 集合没有这个方法名。需要用实际完整 statement ID 核对生产路由、调用者和已合入代码，避免拿不同查询/不同版本做对比；不能未经核对批量扩大拦截范围。当前模拟实现的 `checkIfStrictMode()` 仍固定返回 true，生产必须沿用其真实配置读取，不能把模拟开关当生产配置。

在同一请求、同一参数、同一分页条件下，对比以下四个边界：

| 边界 | 验证内容 | 如果首次在这里出现异常 |
| --- | --- | --- |
| 实际执行 SQL 的 ResultSet | 换表后的 SQL、行数、ID 列标签和数据、连接基数 | 查 JOIN 一对多扩行、重复列名/别名、新旧表差异、分页 SQL；不要直接加 DISTINCT 掩盖合法差异 |
| `invocation.proceed()` 刚返回 | 集合实际类型、size、行实际类型、ID 是否为空 | 查原/复制 ResultMap 的 type、ID 映射、autoMapping、TypeHandler、DTO 继承/同名字段、实际插件链 |
| `addressBook.processResult()` 前后 | 同一集合/行引用、size、顺序、全部普通字段相等；仅敏感显示值变化 | 查生产与本次实现差异、是否重复注册拦截器/重复调用脱敏器 |
| Service 转 DTO 与最终响应 | 行数、ID、分页 total/pages 等元数据 | 查 DTO 拷贝规则、flatMap/addAll、分页容器替换、序列化字段遮蔽 |

只在受控调试或合成测试中检查原值，生产日志仅保留 requestId/statementId、阶段耗时、行数、空 ID 数量和调用计数，不输出敏感数据。

上线验收必须在真实 Mapper/DTO、真实 MyBatis 和分页插件组合上重跑：固定 100 行输入应仍返回 100 行；所有非敏感字段逐项等值；分页元数据保持；SQL 执行次数符合原分页协议（count 和数据查询分开计数）；加解密/Redis 调用量符合上表。截图中尚未解释的 100→300/空 ID 必须在这个边界对比中定位后才能宣称现场问题关闭。

回滚仅撤销本次查询优化，保留前次写入完整性修复。token 内容与协议兼容，无需数据迁移或清库；不通过关闭写入加密或绕过脱敏来降低耗时。

## 生产 docking_type = D3 的补充排查

项目所有者进一步确认：生产的 plaintext role 实际是通讯录记录的 `docking_type`，不是登录角色。当前 `AddressBookHandler` 已对齐：DTO 使用 `dockingType`；Map 支持 `dockingType` / `docking_type`。本地 demo 无该字段时才兼容 `role`，存在但为空时不能回退为明文。没有将 D3 写死为生产白名单；是否允许明文仍由现有配置决定，strict mode 仍优先。

截图选择了 `a.docking_type`，所以没有 `role` 列不代表生产缺少判定字段。截图的第二个字典 JOIN 同样使用该字段：

```sql
LEFT JOIN t_m_announce_data_dictionary c
  ON a.docking_type = c.data_value AND c.data_type = 'dockingType'
```

因此“D3 同时属于明文名单且出现重复行”并不能单独证明明文分支新增了行。若字典中 `('dockingType', 'D3')` 匹配 3 条，每个 D3 联系人就会由 SQL 产生 3 行；第一个 lineType JOIN 也可能扩行。单个联系人的倍数为：

`max(1, lineType 匹配数) × max(1, dockingType 匹配数)`。

使用 SQLite 内存合成数据和截图中的两个 JOIN 已验证：100 条 D3 联系人、3 条 D3 字典记录 → 300 行；100 条 D4 联系人、1 条 D4 字典记录 → 100 行。两组 ID 都不为空，全程没有脱敏器或 MyBatis。此实验验证了机制，不代表已查询生产并确认 D3 字典重复。

只读核验脚本为 `docs/address-book-d3-diagnostics.sql`，先核对字典重复，再比对联系人基数和预期 JOIN 行数。要与某次请求严格比较，必须补齐相同请求过滤条件。截图未展示 SQL 全部 WHERE / ORDER BY，不能直接改写整个生产 Mapper。

若确认字典多重匹配，修复原则是让业务上应为单值的字典关联唯一：

1. 先明确字典是否按机构/租户/版本/有效状态分域，将缺失的业务条件补入 JOIN；截图当前只有 type/value 条件。
2. 若是同一业务作用域内的错误重复，由维护者确认有效项后修复数据，并按真实作用域建立唯一性约束。不能盲目删除记录或直接添加 `(data_type, data_value)` 唯一索引。
3. 如果字典本就允许多值，需定义每个联系人的唯一显示/排序项，再按该业务规则关联。不要任意 `MIN(sort)`、`DISTINCT` 或 Java 按 ID 去重。
4. 如果完整 SQL 的筛选和排序确实都不使用 b/c，才可以移除无用途 JOIN；截图的部分 SELECT 不足以证明这一点。

分别检查 `invocation.proceed()` 刚返回和 `processResult()` 之后的大小，才能界定 SQL/插件链与脱敏阶段的责任。在方法出口观察到 300 行无法确定是哪个阶段产生。若 SQL 已返回 300 行，当前脱敏器应保留这 300 行，而不是悄悄删除重复业务行。

现场进一步确认：`Object result = invocation.proceed()` 刚执行完已返回 300 多行，且只有 D3 记录扩行。这已排除本次调用中后续 `processResult()`、字段脱敏及明文名单分支导致新增行；`getPlaintextRoles()` 在此时尚未调用。下一步只针对此前的实际 SQL、MyBatis 映射与内层插件链核验。结合可见 SQL，优先验证 D3 字典匹配不唯一；仍需字典统计或直接执行同条件 SQL 的结果，才能将具体数据根因确认为第二个 JOIN，而不能仅凭 D3 相关性排除第一个 JOIN 或内层插件。

空 ID 是独立现象：这里选择的是左表 `a.id`，右表一对多匹配不会把非空 `a.id` 清空。如果 ResultSet 的 `a.id` 非空而 DTO 空，应继续检查生产 ResultMap、父子类同名 ID、getter 和后续字段拷贝。

回归已扩展为全 D3、全 D4、D3/D4 混合的 100 行，分别经过 resultType / resultMap / Map 三种真实结果映射和明文/脱敏开关，验证 D3 明文、D4 脱敏、行数/顺序/普通字段保持、三个重复密文每次查询只解密三次；纯 D3 明文查询不写 Redis。另外验证 docking-type 字段为空不能回退到 `role` 放行，以及 strict mode 仍掩码 D3。

补充后的完整 `sensitive-tests` 实测 73 项通过，失败/错误/跳过均为 0。
