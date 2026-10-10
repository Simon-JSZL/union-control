# Maven 源码分发

坐标：`io.github.simon-jszl:union-control-source:1.3.6`，类型 `zip`，classifier `source`。
独立发布 POM 不改变现有业务模块的坐标，也不需要编译 Java。

## 获取源码

```bash
curl -fLO https://maven.aliyun.com/repository/central/io/github/simon-jszl/union-control-source/1.3.6/union-control-source-1.3.6-source.zip
unzip union-control-source-1.3.6-source.zip
```

Central 原始地址为 `https://repo.maven.apache.org/maven2/`，其余路径相同。
阿里云同步可能延迟；Central 已发布不表示镜像或内网代理已刷新。

源码包包含当前工作区的模块源码（包含未被 Git 忽略的新文件，排除已删除文件）、生产 overlay、集成说明及许可证。
保留本地 mock 以便理解工程结构；生产集成时必须遵守 `PROJECT_OVERVIEW.md` 的排除要求。
不包含本地 application 配置、schema.sql、src/test、构建产物或凭据，不能作为开箱即用的部署包。
`SOURCE-MANIFEST.json` 记录基准 Git commit 和每个文件的 SHA256；有未提交内容时以文件哈希为准。

## 构建与发布

1. 同步更新 `package.json` 与 `publishing/pom.xml` 的版本。Central release 不可覆盖。
2. 执行 `python3 publishing/build.py`，检查 `target/maven-central/<版本>/repository/`。
3. 使用自己的 GPG 密钥为 `.pom` 和 `-source.zip` 生成 ASCII detached `.asc` 签名；公钥需发布到 Central 支持的 keyserver。
4. 将 `repository/` 内的坐标目录打成 ZIP，上传 Central Portal。必须保留 `io/github/...` 目录层级。
5. 验证通过后发布，并分别检查 Central 与阿里云的下载内容及 SHA256。

凭据、签名私钥和口令只能保存在仓库外，不得提交。
构建脚本只打包，不执行上传。发布规则见 [Central 要求](https://central.sonatype.org/publish/requirements/)
及 [Bundle 上传说明](https://central.sonatype.org/publish/publish-portal-upload/)。
