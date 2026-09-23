# Sensitive 写入基线回归测试

使用 Java 8，从仓库根目录运行：

```sh
mvn -pl ark-control -am -Plocal-sensitive-compat,sensitive-tests test
```

`sensitive-tests` 明确选择 `import-test/sensitive` 作为测试源，输出到独立的
`target/sensitive-test-classes`，不会与被忽略的 `src/test` 同名旧测试混用。
JUnit 4 / Mockito 沿用现有依赖，不新增框架。

- `interceptor`：开关不能绕过加密、失败不执行 SQL、参数恢复、通讯录及查询行为。
- `SensitiveWriteJdbcTest`：真实 MyBatis SIMPLE/REUSE/BATCH Executor，JCE AES256 测试代理，mock JDBC 验证实际绑定值、foreach、token 回写与生成主键。
- `SensitiveReadJdbcTest`：真实 MyBatis XML/结果映射，验证 resultType/resultMap、继承 ID、全部普通字段、100 行与顺序不变、一次 JDBC 查询及读取去重。
- `reveal`：三类注解、参数容器/别名、普通字符保留、token/Redis 故障及输入修改原子性。
- `crypto`：拒绝无效密码服务返回值、带标签加密不重扫密文、存储 codec 保真。

2026-09-23 补充 docking_type / D3 验证后实测 `test`：73 项测试通过，失败/错误/跳过均为 0。

报告：`ark-control/target/surefire-reports`；JaCoCo：`ark-control/target/site/jacoco/index.html`。
覆盖率必须以本次实际报告为准，不沿用旧版本百分比。

基线与生产验证边界见 `docs/sensitive-write-baseline.md`。
查询优化、调用次数与现场验收步骤见 `docs/sensitive-read-integrity.md`。
默认旧测试的 `test-compile` 仍有既存构造器不匹配；本次独立 profile 可正常执行。
