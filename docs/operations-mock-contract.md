# 运维查询接口与生产结构 mock

本次实现用于本仓库的本地联调，沿用 `RunningAnalysisMockService`、既有 Dubbo 引用和 `/agent` 鉴权入口。没有新增数据库表或生产 SQL 实现。生产集成应以真实查询替换整个 mock 服务及资源，不能把合成值接入生产查询。

## 接口

| 路径 | interfaceName | 数据 |
| --- | --- | --- |
| `/agent/queryChanges` | 无，body 直接为查询条件 | 完整变更记录，含通知原始字段与执行评价 |
| `/agent/queryFaults` | 无，body 直接为查询条件 | 故障记录；九月至十月场景包含时间线、处置和复盘，七月摘要保留未知字段 |
| `/agent/queryBigData` | `t_indicator_base_info` | 机构维度 |
| 同上 | `t_indicator_change_info` | 常规变更指标，20 个非分区字段 |
| 同上 | `t_indicator_change_info_all_biz` | 全业务变更指标，目前仅确认八个消费字段 |
| 同上 | `t_indicator_fault_info` | 故障指标，16 个非分区字段 |
| 同上 | `t_indicator_sys_code` | 返回码指标，5 个非分区字段，另保留 ymd |
| 同上 | `t_indicator_accessed_api_info` | 联动能力，21 个非分区字段，另保留快照日 |
| 同上 | `t_indicator_operation_security_cap` | 年度能力，26 个非分区字段，另保留 year |

新大数据请求为 `{"interfaceName":"…","params":{…}}`。既有 `runing_cnt.bank` / `runing_cnt.full_link` 路由、日期格式和超时策略不变。五家机构统一读取日期键控 fixture，返回原生 `running_cnt.total_cnt`、`total_cnt_fail`、`total_time_cost`、`bank_code`、`biz_type`、`ymd` 字段以及兼容的 `statDate`、`orgCode`、`orgName`、`totalTxnCnt`、`failTxnCnt`、`avgCostMs`、百分比字段。成功率与失败率按相同计数计算，保留六位小数。全链路是当前日期样本机构的汇总，耗时由累计分子除以总笔数，不能再与机构数相加。范围外及未知机构返回空数组，不临时制造数据。变更和故障明细分别只通过 `queryChanges` 和 `queryFaults` 查询。

运行结果声明 `mock_data=true`、`coverage=partial`、`coverage_note`、`duration_unit`。毫秒是本 mock 的明确约定，不改变生产目录中原始耗时单位待确认的事实。无生产 SLA、分钟级时序或历史版本回放；不能根据合成场景评价真实机构运行质量。

CAS 请求复用 Web 现有 Shiro 鉴权；定时任务 Scheduled token 由 `/agent/**` 的 `agentAuth` 过滤器额外校验，两者共用接口的 `@RequiresPermissions` 权限注解。运维查询按原业务 JSON 入参调用，不统一注入或要求 `userId`、`orgCode`、`roleId`；鉴权身份不作为运维查询的必填参数。mock 数据对本地已授权测试用户共享，不实现生产机构数据授权策略；生产授权范围必须由现有 Web/业务边界落实。

## 数据依据与边界

静态数据：`ark-control/src/main/resources/mock/operations-v1.json`。字段依据用户提供的表字段字典、各表 SQL，以及 `indicator_detail` 下的 notice、jira、running 样例。**机构、编号、时间、文本和业务数值均为合成联调值，不复制真实人员、联系方式或完整生产记录。**

- `details.source_fields` 保留 notice 的全部原始键、`closeChannelDTO`、`auditInfos`；额外的 `execution` 对象保留 jira 样例的全部执行评价键。
- `executeDesc` 保持生产中的 **JSON 字符串** 形态，并将其中的 `faultNo` 提取为关联编号。故障原始字段保留 `faultNo`、`faultCause`、`faultLevel`。七月摘要的 `cause_status=unknown`；新增案例的根因、时间线与验证是明确的合成场景设定，非从原因分类推断。
- `executeTime` 保留原字段，不映射成实际开始时间。故障发现、发生、恢复、处置时间线、复盘与验证缺失时返回 `null`，列在 `unavailable_fields`。不能把关联变更的计划时间当作故障时间。
- 流程状态、规则轨迹和人工意见是明确标记的合成场景；不是从 `dataStatus` / `autoAuditStatus` 数字推导的生产映射。fixture 的 `enums` 保留生产样例中观察到的合法代码子集，因此“合法但本批没有匹配行”仍返回空结果。
- 指标 `values` 保留各表独立的原字段拼写，包括 `non_compliace_change`、`assert_control_cap`。时间戳、复合文本和数值分别保留，不将 API 开启记录改成布尔值。维度和分区放入 `dimensions`。
- 故障时长为分钟，变更停机为秒；运行耗时在 mock 中约定为毫秒；生产单位仍待确认。普通与豁免指标同时保留，全业务表独立返回。
- all_biz 的完整 DDL/生成 SQL 未提供，不能宣称这八个字段构成其完整生产 schema。未知业务评价和不能确认的聚合值返回空，并保留缺口。

## 固定场景与可查询范围

机构：`C_MOCK_A`（联调样例银行甲，别名“样例甲”“甲行”）、`C_MOCK_B`（联调样例银行乙，代理/父机构为甲）。名称“样例”返回两个候选。

| 报备编号 | 场景 |
| --- | --- |
| `MOCK-CH-SUCCESS` | 7 月 11–12 日历史成功对照，无已关联故障 |
| `MOCK-CH-DRILL` | 7 月 13 日演练，执行评价为“优”，仍关联演练故障摘要 |
| `MOCK-CH-CROSS` | 7 月 18 日 22:00 至 19 日 07:00 跨日变更，关联 `MOCK-FAULT-CROSS` |
| `MOCK-CH-FOLLOWUP` | 7 月 20 日回切报备，文本追述 19 日情况，显式关联前序报备 |
| `MOCK-CH-PENDING` | 9 月 30 日至 10 月 1 日的人工待审场景 |
| `MOCK-CH-CANCEL` | 已取消的合成报备 |

完整分析样本覆盖 **2026-09-01 至 2026-10-04**，包含五家机构逐日运行、四类日指标、每日 API 快照及 2026 年能力评分。三家原演示银行与甲乙两家均进入同一维表。保留七月甲乙两家的历史案例，不声称七月五家均有覆盖。

新增 6 条变更（9/16、9/22、9/24、9/26、9/29、10/2），其中 9/22、9/26、10/2 分别关联 8、12、5 分钟故障。运行失败增量、耗时上升、超时返回码、变更停机秒数、故障分钟数和双向关联编号一致；成功变更作为对照。故障影响笔数仅为当天失败中的事件增量，另有背景失败。日指标的变更数按这些新增场景的执行日期统计。仍未确认生产定义的业务评价字段保留 null，不伪造口径。

`import-test/operations/extend_fixture.py` 可重复生成同一批固定样本，不读取当前时间，不随查询窗口改写日期。日期范围固定是有意的：需要模拟新周期时显式扩展场景和结束日期，并重新验证；接口不会将未覆盖日期当作零事件。更新时点为 2026-10-05 09:00 +08:00，仅支持事后分析。

可选数组参数必须是 JSON 数组，例如 `org_codes: ["C_MOCK_A"]`，不能传字符串形式的 `"[\"C_MOCK_A\"]"`。Python 在请求到达 Java 前会拒绝后者；这不是 fixture 数据缺失。跨语言校验覆盖真实数组参数、时间过滤和状态组合。

## 查询规则

参数与 py-app 的 `app/tools/operations_contracts.py` 对齐。当前、历史、按编号、待审均使用同一对象接口。

- 条件之间 AND，同一列表内 OR；空列表、错误类型、未知字段报错。原生枚举按样例字典验证，缺少字典的字段明确拒绝，不静默忽略。
- `time_filters` 时间点左闭右开，planned/actual/impact 为区间相交。缺失时间的记录不作为匹配结果，并在 gaps 中说明，不能据空结果认定不存在事件。
- 文本在指定字段进行不区分大小写的字面子串匹配；`%`、`_` 没有通配符作用，`any/all` 按关键词组合。
- 多个 `periods` 独立计算，`period_index` 标识输入区间；重叠日期可以出现在不同对照区间，但不混加。
- `aggregation=period` 只累加已确认可加字段，按机构、区间及返回码分组。不跨返回码、机构或年累加，不将未知值变成 0；其余不可聚合字段为 null。all_biz 尚未确认聚合规则，返回 `unsupported_aggregation`。
- 默认 `page_size=20`，上限 100。稳定排序追加来源编号及区间编号。游标签名绑定查询、固定数据快照和页大小；变更条件或篡改后返回 `invalid_cursor`。游标不承担跨用户的数据授权。重启或切换 mock 实例后需从第一页重新查询。
- `version=mock-v1` 匹配该合成快照，其他版本返回空。样例没有版本历史，所有 `as_of` 返回 HTTP 409 `history_unavailable`，不伪造历史可见状态。

响应为 `{"success":true,"data":{items,total,next_cursor,applied_query,data_updated_at,coverage,gaps,unavailable_fields,units,definitions}}`，兼容现有 py 客户端解包。`applied_query` 包含实际使用的默认条件；每条记录包含来源、版本和不可用字段。所有新 mock 查询固定 `coverage=partial`，并声明合成来源。单条文本不截断；py 仍执行完整页大小上限保护。

新查询错误返回 HTTP 422（invalid_query、unsupported_filter、unsupported_enum、unsupported_aggregation）或 HTTP 409（history_unavailable、invalid_cursor），只返回安全错误码和提示，不带原始请求/记录内容。

## 联调示例

人工审核队列：

```json
{"workflow_states":["manual_pending"],"page_size":20}
```

跨日变更：

```json
{"org_codes":["C_MOCK_A"],"time_filters":[{"field":"planned","start":"2026-07-19T00:00:00+08:00","end":"2026-07-20T00:00:00+08:00"}]}
```

查询关联故障：

```json
{"related_change_ids":["MOCK-CH-CROSS"]}
```

故障指标对照：

```json
{"interfaceName":"t_indicator_fault_info","params":{"org_codes":["C_MOCK_A","C_MOCK_B"],"periods":[{"start_date":"2026-07-18","end_date":"2026-07-20"}],"aggregation":"rows"}}
```

## 验证

使用 Java 8，无需数据库或启动服务：

```bash
mvn -pl ark-control -am -Dtest.source=import-test/operations \
  -Dtest=OperationsMockQueryTest -Dsurefire.failIfNoSpecifiedTests=false test
/path/to/union-py-app/.venv/bin/python \
  ark-control/import-test/operations/verify_py_contract.py /path/to/union-py-app \
  /path/to/indicator_detail
```

Java 测试输出 `ark-control/target/operations-contract-samples.json`。Python 校验实际 Java 返回页经过 py 的真实查询函数后仍符合类型、参数回显和完整记录契约；可选的最后一个目录用于比对生产 notice/jira 原始字段集合。它不需要生产数据作为运行时依赖，不调用真实模型。
