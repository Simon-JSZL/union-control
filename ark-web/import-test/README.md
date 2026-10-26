# 导入单测

测试名称为源类名加 `Test`，Java 文件按包组织；mock 实现、配置类、启动入口和无实现的接口/注解不生成空测试。
复用现有 JUnit 4、Mockito、AssertJ 和 Spring Test。当前本地 POM 没有 PowerMock；这些测试无需静态替换或新增依赖。

在仓库根使用 Java 8 运行（两个模块的默认测试目录均为 `import-test`）：

```bash
mvn clean test
mvn clean org.jacoco:jacoco-maven-plugin:0.8.6:prepare-agent test org.jacoco:jacoco-maven-plugin:0.8.6:report
```

覆盖率报告位于本模块的 `target/site/jacoco/index.html`，同时提供 XML/CSV；查看对应业务类，原始报告也包含未要求测试的 mock、配置及启动类。
原有本地回归可单独运行 `mvn clean test -Dtest.source=src/test/java`。生产集成时按生产包名调整导入和包声明，并使用宿主已有的测试依赖；不要整体替换生产 POM。
