# Sensitive 写入基线回归测试

使用 Java 8，从仓库根目录运行：

```sh
mvn -pl ark-control -am -Plocal-sensitive-compat,sensitive-tests test
```

`sensitive-tests` 明确选择 `import-test/sensitive` 作为测试源，输出到独立的
`target/sensitive-test-classes`，不会与被忽略的 `src/test` 同名旧测试混用。
JUnit 4 / Mockito 沿用现有依赖，不新增框架。

5 个对应单测统一放在本目录（不是生产 `src/main/java`），类名为源类名加 `Test`，
Java package 仍与被测类一致，便于生产集成时放入各自的测试包：

- `AESInterceptorTest.java`
- `AddressBookHandlerTest.java`
- `RedisRevealTokenStoreTest.java`
- `SensitiveRevealProcessorTest.java`
- `SensitiveRevealServiceImplTest.java`

旧的 `reveal/`、`interceptor/` 下同名测试已删除；本地被忽略的 `src/test` 中同名副本也已清理。
其它 JDBC/crypto 回归测试继续保留。

拦截器写入测试继续使用生产 statement ID；脱敏开关测试使用 `REVEAL_LIST` 中的
Demo 查询。新增回归遍历 `ADDRESS_BOOK` / `DECRYPT` 的全部生产查询，验证严格模式
开启或关闭时均返回明文且不访问 reveal Redis。JDBC 查询回归验证生产通讯录返回明文。
生产通讯录写入仍由宿主手动加密，不加入查询迁移名单。


- `AESInterceptorTest` / `AddressBookHandlerTest`：开关组合、默认值及自定义角色、失败不执行 SQL、参数恢复、通讯录及查询行为。
- `SensitiveWriteJdbcTest`：真实 MyBatis SIMPLE/REUSE/BATCH Executor，JCE AES256 测试代理，mock JDBC 验证实际绑定值、foreach、token 回写与生成主键。
- `SensitiveReadJdbcTest`：真实 MyBatis XML/结果映射，验证 resultType/resultMap、继承 ID、全部普通字段、100 行与顺序不变、一次 JDBC 查询及读取去重。
- 三个 sensitive 服务测试：三类注解、参数容器/别名、普通字符保留、token/Redis 故障、异常信息隔离及输入修改原子性。
- `crypto`：拒绝无效密码服务返回值、带标签加密不重扫密文、存储 codec 保真。

2026-09-24 重写后，5 个对应测试共 70 项用例通过，每个 `@Test` 方法直接包含
JUnit `assert...()` 或 `fail()`，mock/spy 使用已有 Mockito，不新增依赖。
加上原有 JDBC/crypto 回归测试，共 78 项通过，失败/错误/跳过均为 0。

单独测量这 5 个新测试（`clean` 清除旧 JaCoCo 数据）：

```sh
mvn -pl ark-control -am -Plocal-sensitive-compat,sensitive-tests clean test \
  -Dtest=RedisRevealTokenStoreTest,SensitiveRevealServiceImplTest,SensitiveRevealProcessorTest,AddressBookHandlerTest,AESInterceptorTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

| 源文件（包含内部类） | JaCoCo 分支覆盖 | 行覆盖 |
| --- | --- | --- |
| RedisRevealTokenStore | 22/22（100%） | 40/42 |
| SensitiveRevealServiceImpl | 12/12（100%） | 36/36 |
| SensitiveRevealProcessor | 220/222（99.10%） | 300/304 |
| AddressBookHandler | 24/24（100%） | 35/35 |
| AESInterceptor | 64/66（96.97%） | 111/114 |

SensitiveRevealProcessor 剩余两个分支都是继承链遍历的 `type != null` 为 false：正常对象的继承链在
`Object.class` 已退出，因此无法从公开 API 触发。未覆盖行分别是 JDK 必备 SHA-256
算法缺失、已设置可访问字段的 IllegalAccessException、尚未接入缓存的角色配置回退。
AESInterceptor 另有两个未覆盖分支属于通讯录迁移路径的非 SELECT 分支；当前生产迁移名单仅包含查询，测试不借用 Demo 写入路径填满分支覆盖率。
不通过修改生产逻辑、过滤覆盖率或制造 JVM 故障来抬高覆盖率。

`strictMode`、`revealEnabled`、`plaintextRoles` 按“代码默认值，commonddcache 取到配置则覆盖，
取不到则保留默认值”的生产约定理解。当前源码中 reveal enabled 和 plaintext roles 的缓存调用为注释，strict mode 也是
固定值；测试验证当前默认值（true / true / 001,002,003），再通过 Mockito spy 模拟解析后的
配置返回值，覆盖所有开关组合以及自定义/空角色集合。**spy 返回配置不等于验证缓存读取和异常回退**；
生产接入 commonddcache 后须针对真实缓存依赖补充成功、缺失及异常回退测试。

生产 sonar2 流水线和 Quality Gate 配置未提供，无法本地确认门禁通过。
生产流水线须识别这些文件为测试源（可按 package 放入宿主 `src/test/java`），执行测试并导入
对应宿主的 JaCoCo XML。当前仓库报告仅证明上述版本和源码的本地结果，不能替代实际流水线验收。

报告：`ark-control/target/surefire-reports`；JaCoCo：`ark-control/target/site/jacoco/index.html`。
覆盖率必须以本次实际报告为准，不沿用旧版本百分比。

基线与生产验证边界见 `docs/sensitive-write-baseline.md`。
查询优化、调用次数与现场验收步骤见 `docs/sensitive-read-integrity.md`。
默认旧测试的 `test-compile` 仍有既存构造器不匹配；本次独立 profile 可正常执行。
