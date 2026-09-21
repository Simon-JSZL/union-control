# Sensitive 单元测试

测试文件与生产类一一对应：

- `interceptor/AESInterceptorTest.java`
- `interceptor/AddressBookHandlerTest.java`
- `reveal/SensitiveRevealProcessorTest.java`
- `reveal/SensitiveRevealServiceImplTest.java`
- `reveal/RedisRevealTokenStoreTest.java`

每个测试方法包含 JUnit assert，依赖通过 Mockito mock。文件沿用生产类的 Java package，以访问包级可见类与方法，无需修改生产代码可见性。

使用 Java 8，从仓库根目录运行：

```sh
mvn -pl ark-control -am -Plocal-sensitive-compat,sensitive-tests clean test
```

独立 profile 沿用项目原有 Mockito 1.10.19，只编译此目录中的五个测试。不使用静态 mock 或 final 类 mock；final 处理类使用真实实例，仅通过 Mockito 模拟 Redis、加解密等依赖。默认构建保留旧测试及其依赖；此 profile 使用独立的 `target/sensitive-test-classes` 输出目录，避免与已有本地同名测试冲突。JaCoCo 报告位于 `ark-control/target/site/jacoco/index.html`。现有 `.gitignore` 将 `src/test` 保留为本地文件，本次未修改该规则。

## 实测结果

43 个测试通过，失败/错误/跳过均为 0。以下为 JaCoCo 源文件统计（包含内部类），未过滤未覆盖行或分支：

| 生产文件 | 行覆盖 | 分支覆盖 |
| --- | --- | --- |
| AESInterceptor | 106/110（96.36%） | 62/64（96.88%） |
| AddressBookHandler | 42/42（100%） | 30/30（100%） |
| SensitiveRevealProcessor | 171/177（96.61%） | 113/114（99.12%） |
| SensitiveRevealServiceImpl | 36/36（100%） | 12/12（100%） |
| RedisRevealTokenStore | 40/42（95.24%） | 22/22（100%） |

尚未达到全部 100%，原因如下：

- AESInterceptor：`checkIfStrictMode()` 固定返回 true，使第 160 行明文解密及第 218 行角色白名单路径不可达；`getPlaintextRoles()` 固定传入合法常量，使第 182、183、185 行异常恢复路径不可达。
- SensitiveRevealProcessor：第 71–72 行捕获的 CheckException 已被内部 reveal 处理消化；第 224–225、236–237 行 IllegalAccessException 位于成功 `setAccessible(true)` 后，正常访问无法触发；第 196 行父类遍历先在 Object.class 退出，无法走到 type == null。

- RedisRevealTokenStore：SHA-256 是标准 JDK 提供的算法，正常环境无法触发 NoSuchAlgorithmException；为兼容 Mockito 1.10.19，移除了依赖新版静态 mock 的算法缺失测试。

这些路径无法在当前实现下通过正常输入全部覆盖；本次只补测试与测试运行配置，未修改生产代码或篡改覆盖率结果。

默认旧测试的 `test-compile` 另有既存错误：`SensitiveDataDemoServiceImplTest` 仍使用四参数构造器，而当前生产类只有三参数构造器；本次未改动范围外旧测试。
